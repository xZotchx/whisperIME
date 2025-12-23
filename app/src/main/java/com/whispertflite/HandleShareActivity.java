package com.whispertflite;

import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class HandleShareActivity extends AppCompatActivity {
    private static final String TAG = "HandleShareActivity";
    private static final int TARGET_SAMPLE_RATE = 16000;

    private TextView transcriptionTextView;
    private ProgressBar progressBar;

    private Whisper mWhisper = null;
    private SharedPreferences sp = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_handle_share);
        setFinishOnTouchOutside(true);

        transcriptionTextView = findViewById(R.id.transcriptionTextView);
        progressBar = findViewById(R.id.progressBar);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("audio/")) {
            Uri audioUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (audioUri != null) {
                transcriptionTextView.setText(R.string.share_waiting);
                progressBar.setVisibility(View.VISIBLE);
                new Thread(() -> processAudio(audioUri)).start();
            }
        } else {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void processAudio(Uri audioUri) {
        File tempFile = null;
        try {
            initializeWhisper();

            tempFile = saveUriToTempFile(audioUri);
            if (tempFile == null) {
                showError("Could not access shared audio file.");
                return;
            }

            byte[] fullPcmData = decodeAudioToPcm(tempFile);
            if (fullPcmData == null) {
                showError("Decoding failed. Audio format might not be supported.");
                return;
            }

            final int bytesPerSample = 2;
            final int chunkDurationSeconds = 30;
            final int chunkSizeInBytes = TARGET_SAMPLE_RATE * chunkDurationSeconds * bytesPerSample;

            StringBuilder fullTranscription = new StringBuilder();
            final StringBuilder languageDetected = new StringBuilder();

            int offset = 0;
            while (offset < fullPcmData.length) {
                if (isFinishing() || isDestroyed()) {
                    Log.d(TAG, "Activity is finishing, stopping transcription thread.");
                    break;
                }
                int length = Math.min(chunkSizeInBytes, fullPcmData.length - offset);
                byte[] chunkPcmData = Arrays.copyOfRange(fullPcmData, offset, offset + length);

                final CountDownLatch latch = new CountDownLatch(1);

                mWhisper.setListener(new Whisper.WhisperListener() {
                    @Override
                    public void onUpdateReceived(String message) { }

                    @Override
                    public void onResultReceived(WhisperResult whisperResult) {
                        String resultText = whisperResult.getResult();
                        if (resultText != null && !resultText.trim().isEmpty()) {
                            if (whisperResult.getLanguage().equals("zh")) {
                                boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                                resultText = simpleChinese ? ZhConverterUtil.toSimple(resultText) : ZhConverterUtil.toTraditional(resultText);
                            }
                            fullTranscription.append(resultText.trim()).append(" ");

                            if (languageDetected.length() == 0) {
                                languageDetected.append(new Locale(whisperResult.getLanguage()).getDisplayLanguage());
                            }
                        }
                        latch.countDown();
                    }
                });

                RecordBuffer.setOutputBuffer(chunkPcmData);
                mWhisper.start();

                boolean finishedInTime = latch.await(45, TimeUnit.SECONDS);
                if (!finishedInTime) {
                    Log.e(TAG, "Timeout during chunk transcription.");
                }

                offset += length;
            }

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (fullTranscription.length() > 0) {
                    String finalText = String.format(getString(R.string.share_lang_detected), languageDetected)
                            + "\n\n" + fullTranscription.toString().trim();
                    transcriptionTextView.setText(finalText);
                } else {
                    transcriptionTextView.setText(R.string.share_error_generic);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing audio", e);
            showError("Error: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    Log.w(TAG, "Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    private void initializeWhisper() throws IOException {
        File sdcardDataFolder = this.getExternalFilesDir(null);
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        String modelName = sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW);
        File modelFile = new File(sdcardDataFolder, modelName);

        boolean isMultilingual = !modelName.endsWith(ENGLISH_ONLY_MODEL_EXTENSION);
        String vocabFileName = isMultilingual ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        if (!modelFile.exists() || !vocabFile.exists()) {
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.share_error_model, Toast.LENGTH_LONG).show();
                finish();
            });
            throw new IOException("Whisper models not found.");
        }

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingual);
        mWhisper.setAction(Whisper.Action.TRANSCRIBE);
        mWhisper.setLanguage(-1);
    }

    private File saveUriToTempFile(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return null;
            File tempFile = File.createTempFile("shared_audio", ".tmp", getCacheDir());
            tempFile.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return tempFile;
        } catch (IOException e) {
            Log.e(TAG, "Error saving URI to temp file", e);
            return null;
        }
    }

    private byte[] decodeAudioToPcm(File inputFile) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputFile.getAbsolutePath());
        MediaFormat format = null;
        int audioTrackIndex = -1;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                break;
            }
        }

        if (audioTrackIndex == -1) {
            extractor.release();
            return null;
        }

        extractor.selectTrack(audioTrackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
            extractor.release();
            return null;
        }
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteArrayOutputStream pcmOutputStream = new ByteArrayOutputStream();

        boolean isEOS = false;
        final long TIMEOUT_US = 10000;

        while (!isEOS) {
            int inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inputBufIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputBufIndex);
                if (inputBuffer != null) {
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outputBufIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outputBufIndex >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufIndex);
                if (outputBuffer != null) {
                    byte[] pcmChunk = new byte[info.size];
                    outputBuffer.get(pcmChunk);
                    outputBuffer.clear();
                    pcmOutputStream.write(pcmChunk);
                }
                codec.releaseOutputBuffer(outputBufIndex, false);
            }
        }
        codec.stop();
        codec.release();
        extractor.release();

        int originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        byte[] decodedPcm = pcmOutputStream.toByteArray();

        byte[] monoPcm;
        if (channelCount == 2) {
            monoPcm = convertStereoToMono(decodedPcm);
        } else {
            monoPcm = decodedPcm;
        }

        byte[] finalPcm;
        if (originalSampleRate != TARGET_SAMPLE_RATE) {
            finalPcm = resamplePcm(monoPcm, originalSampleRate);
        } else {
            finalPcm = monoPcm;
        }

        return finalPcm;
    }

    private byte[] convertStereoToMono(byte[] stereoPcm) {
        byte[] monoPcm = new byte[stereoPcm.length / 2];
        for (int i = 0; i < monoPcm.length / 2; i++) {
            int leftSample = (stereoPcm[i*4+1] << 8) | (stereoPcm[i*4] & 0xFF);
            int rightSample = (stereoPcm[i*4+3] << 8) | (stereoPcm[i*4+2] & 0xFF);
            int avgSample = (leftSample + rightSample) / 2;
            monoPcm[i*2] = (byte) (avgSample & 0xFF);
            monoPcm[i*2+1] = (byte) (avgSample >> 8);
        }
        return monoPcm;
    }

    private byte[] resamplePcm(byte[] pcmData, int fromRate) {
        int numSamples = pcmData.length / 2;
        int newNumSamples = (int)Math.round((double)numSamples * TARGET_SAMPLE_RATE / fromRate);
        byte[] resampledData = new byte[newNumSamples * 2];
        double ratio = (double) (numSamples - 1) / (newNumSamples - 1);

        for (int i = 0; i < newNumSamples; i++) {
            double oldIndex = i * ratio;
            int index1 = (int) oldIndex;
            int index2 = Math.min(index1 + 1, numSamples - 1);
            double fraction = oldIndex - index1;

            short sample1 = (short) ((pcmData[index1*2+1] << 8) | (pcmData[index1*2] & 0xFF));
            short sample2 = (short) ((pcmData[index2*2+1] << 8) | (pcmData[index2*2] & 0xFF));
            short newSample = (short) (sample1 * (1 - fraction) + sample2 * fraction);

            resampledData[i*2] = (byte) (newSample & 0xFF);
            resampledData[i*2+1] = (byte) (newSample >> 8);
        }
        return resampledData;
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            transcriptionTextView.setText(message);
            Toast.makeText(HandleShareActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }
}