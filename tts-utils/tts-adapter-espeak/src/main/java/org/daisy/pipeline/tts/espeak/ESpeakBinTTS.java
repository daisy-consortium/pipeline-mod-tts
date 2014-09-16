package org.daisy.pipeline.tts.espeak;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import net.sf.saxon.s9api.XdmNode;

import org.daisy.common.shell.BinaryFinder;
import org.daisy.pipeline.audio.AudioBuffer;
import org.daisy.pipeline.tts.AbstractTTSService;
import org.daisy.pipeline.tts.AudioBufferAllocator;
import org.daisy.pipeline.tts.AudioBufferAllocator.MemoryException;
import org.daisy.pipeline.tts.SoundUtil;
import org.daisy.pipeline.tts.TTSRegistry.TTSResource;
import org.daisy.pipeline.tts.TTSServiceUtil;
import org.daisy.pipeline.tts.Voice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

/**
 * This synthesizer uses directly the eSpeak binary. The voice names are used
 * for identifying the voices, but, for future improvements, their corresponding
 * file names could be used instead (with the -v option instead of <ssml:voice
 * name=...>), depending on how the connectors (SAPI etc.) manage the eSpeak
 * voices.
 */
public class ESpeakBinTTS extends AbstractTTSService {

	private Logger mLogger = LoggerFactory.getLogger(ESpeakBinTTS.class);

	private AudioFormat mAudioFormat;
	private String[] mCmd;
	private String mEspeakPath;
	private final static int MIN_CHUNK_SIZE = 2048;

	public void onBeforeOneExecution() throws SynthesisException, InterruptedException {
		final String property = "espeak.path";
		mEspeakPath = System.getProperty(property);
		if (mEspeakPath == null) {
			Optional<String> epath = BinaryFinder.find("espeak");
			if (!epath.isPresent()) {
				throw new SynthesisException("Cannot find eSpeak's binary and " + property
				        + " is not set");
			}
			mEspeakPath = epath.get();
		}

		mLogger.info("Will use eSpeak binary: " + mEspeakPath);

		// '-m' tells eSpeak to interpret the input as SSML
		// '--stdout' tells eSpeak to print the result on the standard output
		// '--stdin' tells eSpeak to read the SSML from the standard input. It prevents it
		// from complaining about the size of the command line (when the sentences are big).
		mCmd = new String[]{
		        mEspeakPath, "-m", "--stdout", "--stdin"
		};;

		//Test the synthesizer so that the service won't be active if it fails.
		//It sets mAudioFormat too.
		mAudioFormat = null;
		Throwable t = TTSServiceUtil.testTTS(this, "test");
		if (t != null) {
			throw new SynthesisException(t);
		}
	}

	@Override
	public AudioFormat getAudioOutputFormat() {
		return mAudioFormat;
	}

	@Override
	public String getName() {
		return "espeak";
	}

	@Override
	public String getVersion() {
		return "command-line";
	}

	@Override
	public void onAfterOneExecution() {
		mAudioFormat = null;
		mCmd = null;
		mEspeakPath = null;
		super.onAfterOneExecution();
	}

	@Override
	public int getOverallPriority() {
		return Integer.valueOf(System.getProperty("espeak.priority", "1"));
	}

	@Override
	public Collection<Voice> getAvailableVoices() throws SynthesisException,
	        InterruptedException {
		Collection<Voice> result;
		InputStream is;
		Process proc = null;
		Scanner scanner = null;
		Matcher mr;
		try {
			//First: get the list of all the available languages
			Set<String> languages = new HashSet<String>();
			proc = Runtime.getRuntime().exec(new String[]{
			        mEspeakPath, "--voices"
			});
			is = proc.getInputStream();
			mr = Pattern.compile("\\s*[0-9]+\\s+([-a-z]+)").matcher("");
			scanner = new Scanner(is);
			scanner.nextLine(); //headers
			while (scanner.hasNextLine()) {
				mr.reset(scanner.nextLine());
				mr.find();
				languages.add(mr.group(1).split("-")[0]);
			}
			is.close();
			proc.waitFor();
			proc = null;

			//Second: get the list of the voices for the found languages.
			//White spaces are not allowed in voice names
			result = new ArrayList<Voice>();
			mr = Pattern.compile("^\\s*[0-9]+\\s+[-a-z]+\\s+([FM]\\s+)?([^ ]+)").matcher("");
			for (String lang : languages) {
				proc = Runtime.getRuntime().exec(new String[]{
				        mEspeakPath, "--voices=" + lang
				});
				is = proc.getInputStream();
				scanner = new Scanner(is);
				scanner.nextLine(); //headers
				while (scanner.hasNextLine()) {
					mr.reset(scanner.nextLine());
					mr.find();
					result.add(new Voice(getName(), mr.group(2).trim()));
				}
				is.close();
				proc.waitFor();
			}

		} catch (InterruptedException e) {
			if (proc != null) {
				proc.destroy();
			}
			throw e;
		} catch (Exception e) {
			if (proc != null) {
				proc.destroy();
			}
			throw new SynthesisException(e.getMessage(), e.getCause());
		} finally {
			if (scanner != null)
				scanner.close();
		}

		return result;
	}

	@Override
	public Collection<AudioBuffer> synthesize(String sentence, XdmNode xmlSentence,
	        Voice voice, TTSResource threadResources, List<Mark> marks,
	        AudioBufferAllocator bufferAllocator, boolean retry) throws SynthesisException,
	        InterruptedException, MemoryException {
		Collection<AudioBuffer> result = new ArrayList<AudioBuffer>();
		Process p = null;
		try {
			p = Runtime.getRuntime().exec(mCmd);

			//write the SSML
			BufferedOutputStream out = new BufferedOutputStream((p.getOutputStream()));
			out.write(sentence.getBytes("utf-8"));
			out.close();

			//read the wave on the standard output
			BufferedInputStream in = new BufferedInputStream(p.getInputStream());
			AudioInputStream fi = AudioSystem.getAudioInputStream(in);

			if (mAudioFormat == null)
				mAudioFormat = fi.getFormat();

			while (true) {
				AudioBuffer b = bufferAllocator
				        .allocateBuffer(MIN_CHUNK_SIZE + fi.available());
				int ret = fi.read(b.data, 0, b.size);
				if (ret == -1) {
					//note: perhaps it would be better to call allocateBuffer()
					//somewhere else in order to avoid this extra call:
					bufferAllocator.releaseBuffer(b);
					break;
				}
				b.size = ret;
				result.add(b);
			}
			fi.close();
			p.waitFor();
		} catch (MemoryException e) {
			SoundUtil.cancelFootPrint(result, bufferAllocator);
			p.destroy();
			throw e;
		} catch (InterruptedException e) {
			SoundUtil.cancelFootPrint(result, bufferAllocator);
			if (p != null)
				p.destroy();
			throw e;
		} catch (Exception e) {
			SoundUtil.cancelFootPrint(result, bufferAllocator);
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (p != null)
				p.destroy();
			throw new SynthesisException(e.getMessage() + " text: "
			        + sentence.substring(0, Math.min(sentence.length(), 100)) + "...", e);
		}

		return result;
	}
}
