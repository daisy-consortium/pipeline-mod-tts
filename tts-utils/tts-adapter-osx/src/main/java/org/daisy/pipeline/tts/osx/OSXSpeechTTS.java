package org.daisy.pipeline.tts.osx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import net.sf.saxon.s9api.QName;

import org.daisy.pipeline.tts.BasicSSMLAdapter;
import org.daisy.pipeline.tts.MarkFreeTTSService;
import org.daisy.pipeline.tts.SSMLAdapter;
import org.daisy.pipeline.tts.Voice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This synthesizer uses the OS X "say" command.
 * 
 * This is a naïve implementation which just discards all SSML tagging.
 */
public class OSXSpeechTTS extends MarkFreeTTSService {

	private Logger mLogger = LoggerFactory.getLogger(OSXSpeechTTS.class);

	private AudioFormat mAudioFormat;
	private SSMLAdapter mSSMLAdapter;
	private String mSayPath = "/usr/bin/say";
	private final static int MIN_CHUNK_SIZE = 2048;
	private final static String SAY_PATH = "tts.osxspeech.path";

	public void onBeforeOneExecution() throws SynthesisException {
		mSayPath = System.getProperty(SAY_PATH, "/usr/bin/say");
		mLogger.info("Will use 'say' binary: " + mSayPath);

		mSSMLAdapter = new BasicSSMLAdapter() {

			@Override
			public QName adaptElement(QName elementName) {
				return null;
			}

			@Override
			public String getFooter() {
				return "";
			}
		};

		// Test the synthesizer so that the service won't be active if it fails.
		// It sets mAudioFormat too.
		List<RawAudioBuffer> li = new ArrayList<RawAudioBuffer>();
		mAudioFormat = null;
		Object r = allocateThreadResources();
		try {
			synthesize(
					mSSMLAdapter.getHeader(null) + "test"
							+ mSSMLAdapter.getFooter(), null, r, li);
		} catch (InterruptedException e) {
			throw new SynthesisException(e);
		} finally {
			releaseThreadResources(r);
		}
		if (li.get(0).offsetInOutput <= 500) {
			throw new SynthesisException(
					"the 'say' command did not output audio.");
		}
	}

	@Override
	public AudioFormat getAudioOutputFormat() {
		return mAudioFormat;
	}

	@Override
	public String getName() {
		return "osx-speech";
	}

	@Override
	public String getVersion() {
		return "command-line";
	}

	@Override
	public int getOverallPriority() {
		return Integer.valueOf(System.getProperty("osx-speech.priority", "5"));
	}

	@Override
	public Collection<Voice> getAvailableVoices() throws SynthesisException {
		Collection<Voice> result = new ArrayList<Voice>();
		InputStream is;
		Process proc = null;
		Scanner scanner = null;
		Matcher mr;
		try {
			proc = Runtime.getRuntime().exec(
					new String[] { mSayPath, "-v", "?" });
			is = proc.getInputStream();
			mr = Pattern.compile("(.*?)\\s+\\w{2}_\\w{2}").matcher("");
			scanner = new Scanner(is);
			while (scanner.hasNextLine()) {
				mr.reset(scanner.nextLine());
				mr.find();
				result.add(new Voice(getName(), mr.group(1).trim()));
			}
			is.close();
			proc.waitFor();
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
	public SSMLAdapter getSSMLAdapter() {
		return mSSMLAdapter;
	}

	@Override
	public void synthesize(String ssml, Voice voice, Object threadResources,
			List<RawAudioBuffer> output) throws SynthesisException,
			InterruptedException {
		Process p = null;
		File waveOut = null;
		try {

			waveOut = File.createTempFile("pipeline", ".wav");
			p = Runtime.getRuntime().exec(
					new String[] { mSayPath, "--data-format=LEI16@22050", "-o",
							waveOut.getAbsolutePath() });

			// write the SSML
			BufferedOutputStream out = new BufferedOutputStream(
					(p.getOutputStream()));
			out.write(ssml.getBytes("utf-8"));
			out.close();

			p.waitFor();

			// read the wave on the standard output

			BufferedInputStream in = new BufferedInputStream(
					new FileInputStream(waveOut));
			AudioInputStream fi = AudioSystem.getAudioInputStream(in);

			if (mAudioFormat == null)
				mAudioFormat = fi.getFormat();

			while (true) {
				RawAudioBuffer b = new RawAudioBuffer();
				int toread = MIN_CHUNK_SIZE + fi.available();
				b.output = new byte[toread];
				b.offsetInOutput = fi.read(b.output, 0, toread);
				if (b.offsetInOutput == -1)
					break;
				output.add(b);
			}

			fi.close();
		} catch (InterruptedException e) {
			if (p != null)
				p.destroy();
			throw e;
		} catch (Exception e) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (p != null)
				p.destroy();
			throw new SynthesisException(e.getMessage() + " text: "
					+ ssml.substring(0, Math.min(ssml.length(), 100)) + "...",
					e);
		} finally {
			if (waveOut != null)
				waveOut.delete();
		}
	}
}