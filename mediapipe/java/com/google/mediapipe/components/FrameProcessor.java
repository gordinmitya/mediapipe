// Copyright 2019 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.mediapipe.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.os.Handler;
import android.util.Log;
import com.google.common.base.Preconditions;
import com.google.mediapipe.proto.CalculatorProto.CalculatorGraphConfig;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.AndroidPacketCreator;
import com.google.mediapipe.framework.Graph;
import com.google.mediapipe.framework.GraphService;
import com.google.mediapipe.framework.MediaPipeException;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketCallback;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.framework.SurfaceOutput;
import com.google.mediapipe.framework.TextureFrame;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A {@link com.google.mediapipe.components.TextureFrameProcessor} that sends video frames through a
 * MediaPipe graph and a {@link com.google.mediapipe.components.AudioDataProcessor} that sends audio
 * data samples through a MediaPipe graph.
 */
public class FrameProcessor implements TextureFrameProcessor, AudioDataProcessor {
  private static final String TAG = "FrameProcessor";
  private static final int BYTES_PER_MONO_SAMPLE = 2; // 16 bit PCM encoding.
  private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

  private List<TextureFrameConsumer> videoConsumers = new ArrayList<>();
  private List<AudioDataConsumer> audioConsumers = new ArrayList<>();
  private Graph mediapipeGraph;
  private AndroidPacketCreator packetCreator;
  private OnWillAddFrameListener addFrameListener;
  private ErrorListener asyncErrorListener;
  private String videoInputStream;
  private String videoInputStreamCpu;
  private String videoOutputStream;
  private SurfaceOutput videoSurfaceOutput;
  private final AtomicBoolean started = new AtomicBoolean(false);
  // Input stream of audio data. Can be null.
  private String audioInputStream;
  // Output stream of audio data. Can be null.
  private String audioOutputStream;
  // Number of channels of audio data read in the input stream. This can be only 1 or 2, as
  // AudioRecord supports only AudioFormat.CHANNEL_IN_MONO and AudioFormat.CHANNEL_IN_STEREO.
  private int numAudioChannels = 1;
  // Sample rate of audio data sent to the MediaPipe graph.
  private double audioSampleRate;

  /**
   * Constructor for video input/output.
   *
   * @param context an Android {@link Context}.
   * @param parentNativeContext a native handle to a GL context. The GL context(s) used by the
   *     calculators in the graph will join the parent context's sharegroup, so that textures
   *     generated by the calculators are available in the parent context, and vice versa.
   * @param graphName the name of the file containing the binary representation of the graph.
   * @param inputStream the graph input stream that will receive input video frames.
   * @param outputStream the output stream from which output frames will be produced.
   */
  public FrameProcessor(
      Context context,
      long parentNativeContext,
      String graphName,
      String inputStream,
      @Nullable String outputStream) {
    try {
      initializeGraphAndPacketCreator(context, graphName);
      addVideoStreams(parentNativeContext, inputStream, outputStream);
    } catch (MediaPipeException e) {
      // TODO: do not suppress exceptions here!
      Log.e(TAG, "MediaPipe error: ", e);
    }
  }

  /**
   * Constructor.
   *
   * @param context an Android {@link Context}.
   * @param graphName the name of the file containing the binary representation of the graph.
   */
  public FrameProcessor(Context context, String graphName) {
    initializeGraphAndPacketCreator(context, graphName);
  }

  /**
   * Constructor.
   *
   * @param graphConfig the proto object representation of the graph.
   */
  public FrameProcessor(CalculatorGraphConfig graphConfig) {
    initializeGraphAndPacketCreator(graphConfig);
  }

  public FrameProcessor(byte[] graphData) {
    mediapipeGraph = new Graph();
    mediapipeGraph.loadBinaryGraph(graphData);
    packetCreator = new AndroidPacketCreator(mediapipeGraph);
  }

  /**
   * Initializes a graph for processing data in real time.
   *
   * @param context an Android {@link Context}.
   * @param graphName the name of the file containing the binary representation of the graph.
   */
  private void initializeGraphAndPacketCreator(Context context, String graphName) {
    mediapipeGraph = new Graph();
    if (new File(graphName).isAbsolute()) {
      mediapipeGraph.loadBinaryGraph(graphName);
    } else {
      mediapipeGraph.loadBinaryGraph(
          AndroidAssetUtil.getAssetBytes(context.getAssets(), graphName));
    }
    packetCreator = new AndroidPacketCreator(mediapipeGraph);
  }

  /**
   * Initializes a graph for processing data in real time.
   *
   * @param graphConfig the proto object representation of the graph.
   */
  private void initializeGraphAndPacketCreator(CalculatorGraphConfig graphConfig) {
    mediapipeGraph = new Graph();
    mediapipeGraph.loadBinaryGraph(graphConfig);
    packetCreator = new AndroidPacketCreator(mediapipeGraph);
  }

  /** Callback for errors occurring during processing in the graph. */
  public interface ErrorListener {
    void onError(RuntimeException error);
  }

  /**
   * Sets a callback to be invoked when exceptions are thrown in one FrameProcessor's input
   * callbacks (such as onNewFrame, onNewAudioData).
   *
   * @param listener the callback.
   */
  public void setAsynchronousErrorListener(@Nullable ErrorListener listener) {
    this.asyncErrorListener = listener;
  }

  /**
   * Sets a callback to be invoked when exceptions are thrown in one FrameProcessor's input
   * callbacks (such as onNewFrame, onNewAudioData).
   *
   * @param listener the callback.
   * @param handler if not null, the callback will be posted on this handler.
   */
  public void setAsynchronousErrorListener(
      @Nullable ErrorListener listener, @Nullable Handler handler) {
    setAsynchronousErrorListener(
        handler == null
            ? listener
            : (RuntimeException e) -> {
              handler.post(
                  () -> {
                    listener.onError(e);
                  });
            });
  }

  /**
   * Adds input streams to process video data and output streams that output processed video data.
   *
   * @param parentNativeContext a native handle to a GL context. The GL context(s) used by the
   *     calculators in the graph will join the parent context's sharegroup, so that textures
   *     generated by the calculators are available in the parent context, and vice versa.
   * @param inputStream the graph input stream that will receive input video frames.
   * @param outputStream the output stream from which output frames will be produced.
   */
  public void addVideoStreams(
      long parentNativeContext, @Nullable String inputStream, @Nullable String outputStream) {

    videoInputStream = inputStream;
    videoOutputStream = outputStream;

    mediapipeGraph.setParentGlContext(parentNativeContext);

    if (videoOutputStream != null) {
      mediapipeGraph.addPacketCallback(
          videoOutputStream,
          new PacketCallback() {
            @Override
            public void process(Packet packet) {
              List<TextureFrameConsumer> currentConsumers;
              synchronized (this) {
                currentConsumers = videoConsumers;
              }
              for (TextureFrameConsumer consumer : currentConsumers) {
                // Note: each consumer will release its TextureFrame, so each gets a separate object
                // (though they all reference the same data).
                TextureFrame frame = PacketGetter.getTextureFrame(packet);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                  Log.v(
                      TAG,
                      String.format(
                          "Output tex: %d width: %d height: %d to consumer %h",
                          frame.getTextureName(), frame.getWidth(), frame.getHeight(), consumer));
                }
                consumer.onNewFrame(frame);
              }
            }
          });

      videoSurfaceOutput = mediapipeGraph.addSurfaceOutput(videoOutputStream);
    }
  }

  /**
   * Adds input streams to process audio data and output streams that output processed audio data.
   *
   * @param inputStream the graph input stream that will receive input audio samples.
   * @param outputStream the output stream from which output audio samples will be produced.
   * @param numChannels the number of audio channels in the input audio stream.
   * @param audioSampleRateInHz the sample rate for audio samples in hertz (Hz).
   */
  public void addAudioStreams(
      @Nullable String inputStream,
      @Nullable String outputStream,
      int numChannels,
      double audioSampleRateInHz) {
    audioInputStream = inputStream;
    audioOutputStream = outputStream;
    numAudioChannels = numChannels;
    int audioChannelMask =
        numAudioChannels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
    audioSampleRate = audioSampleRateInHz;

    if (audioInputStream != null) {
      Packet audioHeader = packetCreator.createTimeSeriesHeader(numAudioChannels, audioSampleRate);
      mediapipeGraph.setStreamHeader(audioInputStream, audioHeader);
    }

    if (audioOutputStream != null) {
      AudioFormat audioFormat =
          new AudioFormat.Builder()
              .setEncoding(AUDIO_ENCODING)
              .setSampleRate((int) audioSampleRate)
              .setChannelMask(audioChannelMask)
              .build();
      mediapipeGraph.addPacketCallback(
          audioOutputStream,
          new PacketCallback() {
            @Override
            public void process(Packet packet) {
              List<AudioDataConsumer> currentAudioConsumers;
              synchronized (this) {
                currentAudioConsumers = audioConsumers;
              }
              for (AudioDataConsumer consumer : currentAudioConsumers) {
                byte[] buffer = PacketGetter.getAudioByteData(packet);
                ByteBuffer audioData = ByteBuffer.wrap(buffer);
                consumer.onNewAudioData(audioData, packet.getTimestamp(), audioFormat);
              }
            }
          });
    }
  }

  /**
   * Interface to be used so that this class can receive a callback when onNewFrame has determined
   * it will process an input frame. Can be used to feed packets to accessory streams.
   */
  public interface OnWillAddFrameListener {
    void onWillAddFrame(long timestamp);
  }

  public synchronized <T> void setServiceObject(GraphService<T> service, T object) {
    mediapipeGraph.setServiceObject(service, object);
  }

  public void setInputSidePackets(Map<String, Packet> inputSidePackets) {
    Preconditions.checkState(
        !started.get(), "setInputSidePackets must be called before the graph is started");
    mediapipeGraph.setInputSidePackets(inputSidePackets);
  }

  @Override
  public void setConsumer(TextureFrameConsumer consumer) {
    synchronized (this) {
      videoConsumers = Arrays.asList(consumer);
    }
  }

  @Override
  public void setAudioConsumer(AudioDataConsumer consumer) {
    synchronized (this) {
      audioConsumers = Arrays.asList(consumer);
    }
  }

  public void setVideoInputStreamCpu(String inputStream) {
    videoInputStreamCpu = inputStream;
  }

  /** Adds a callback to the graph to process packets from the specified output stream. */
  public void addPacketCallback(String outputStream, PacketCallback callback) {
    mediapipeGraph.addPacketCallback(outputStream, callback);
  }

  public void addConsumer(TextureFrameConsumer consumer) {
    synchronized (this) {
      List<TextureFrameConsumer> newConsumers = new ArrayList<>(videoConsumers);
      newConsumers.add(consumer);
      videoConsumers = newConsumers;
    }
  }

  public boolean removeConsumer(TextureFrameConsumer listener) {
    boolean existed;
    synchronized (this) {
      List<TextureFrameConsumer> newConsumers = new ArrayList<>(videoConsumers);
      existed = newConsumers.remove(listener);
      videoConsumers = newConsumers;
    }
    return existed;
  }

  /** Gets the {@link Graph} used to run the graph. */
  public Graph getGraph() {
    return mediapipeGraph;
  }

  /** Gets the {@link PacketCreator} associated with the graph. */
  public AndroidPacketCreator getPacketCreator() {
    return packetCreator;
  }

  /** Gets the {@link SurfaceOutput} connected to the video output stream. */
  public SurfaceOutput getVideoSurfaceOutput() {
    return videoSurfaceOutput;
  }

  /** Closes and cleans up the graph. */
  public void close() {
    if (started.get()) {
      try {
        mediapipeGraph.closeAllPacketSources();
        mediapipeGraph.waitUntilGraphDone();
      } catch (MediaPipeException e) {
        // Note: errors during Process are reported at the earliest opportunity,
        // which may be addPacket or waitUntilDone, depending on timing. For consistency,
        // we want to always report them using the same async handler if installed.
        if (asyncErrorListener != null) {
          asyncErrorListener.onError(e);
        } else {
          // TODO: do not suppress exceptions here!
          Log.e(TAG, "Mediapipe error: ", e);
        }
      }

      try {
        mediapipeGraph.tearDown();
      } catch (MediaPipeException e) {
        Log.e(TAG, "Mediapipe error: ", e);
      }
    }
  }

  /**
   * Initializes the graph in advance of receiving frames.
   *
   * <p>Normally the graph is initialized when the first frame arrives. You can optionally call this
   * method to initialize it ahead of time.
   * @throws MediaPipeException for any error status.
   */
  public void preheat() {
    if (!started.getAndSet(true)) {
      startGraph();
    }
  }

  public void setOnWillAddFrameListener(@Nullable OnWillAddFrameListener addFrameListener) {
    this.addFrameListener = addFrameListener;
  }

  /**
   * Returns true if the MediaPipe graph can accept one more input frame.
   *
   * @throws MediaPipeException for any error status.
   */
  private boolean maybeAcceptNewFrame(long timestamp) {
    if (!started.getAndSet(true)) {
      startGraph();
    }
    return true;
  }

  @Override
  public void onNewFrame(TextureFrame frame) {
    Packet imagePacket = null;
    long timestamp = frame.getTimestamp();
    try {
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(
            TAG,
            String.format(
                "Input tex: %d width: %d height: %d",
                frame.getTextureName(), frame.getWidth(), frame.getHeight()));
      }

      if (!maybeAcceptNewFrame(frame.getTimestamp())) {
        return;
      }

      if (addFrameListener != null) {
        addFrameListener.onWillAddFrame(timestamp);
      }

      imagePacket = packetCreator.createGpuBuffer(frame);
      // imagePacket takes ownership of frame and will release it.
      frame = null;

      try {
        // addConsumablePacketToInputStream allows the graph to take exclusive ownership of the
        // packet, which may allow for more memory optimizations.
        mediapipeGraph.addConsumablePacketToInputStream(
            videoInputStream, imagePacket, timestamp);
        // If addConsumablePacket succeeded, we don't need to release the packet ourselves.
        imagePacket = null;
      } catch (MediaPipeException e) {
        // TODO: do not suppress exceptions here!
        if (asyncErrorListener == null) {
          Log.e(TAG, "Mediapipe error: ", e);
        } else {
          throw e;
        }
      }
    } catch (RuntimeException e) {
      if (asyncErrorListener != null) {
        asyncErrorListener.onError(e);
      } else {
        throw e;
      }
    } finally {
      if (imagePacket != null) {
        // In case of error, addConsumablePacketToInputStream will not release the packet, so we
        // have to release it ourselves. (We could also re-try adding, but we don't).
        imagePacket.release();
      }
      if (frame != null) {
        // imagePacket will release frame if it has been created, but if not, we need to
        // release it.
        frame.release();
      }
    }
  }

  /**
   * Accepts a Bitmap to be sent to main input stream at the given timestamp.
   *
   * <p>Note: This requires a graph that takes an ImageFrame instead of a mediapipe::GpuBuffer. An
   * instance of FrameProcessor should only ever use this or the other variant for onNewFrame().
   */
  public void onNewFrame(final Bitmap bitmap, long timestamp) {
    Packet packet = null;
    try {
      if (!maybeAcceptNewFrame(timestamp)) {
        return;
      }

      if (addFrameListener != null) {
        addFrameListener.onWillAddFrame(timestamp);
      }

      packet = getPacketCreator().createRgbImageFrame(bitmap);

      try {
        // addConsumablePacketToInputStream allows the graph to take exclusive ownership of the
        // packet, which may allow for more memory optimizations.
        mediapipeGraph.addConsumablePacketToInputStream(videoInputStreamCpu, packet, timestamp);
        packet = null;
      } catch (MediaPipeException e) {
        // TODO: do not suppress exceptions here!
        if (asyncErrorListener == null) {
          Log.e(TAG, "Mediapipe error: ", e);
        } else {
          throw e;
        }
      }
    } catch (RuntimeException e) {
      if (asyncErrorListener != null) {
        asyncErrorListener.onError(e);
      } else {
        throw e;
      }
    } finally {
      if (packet != null) {
        packet.release();
      }
    }
  }

  public void waitUntilIdle() {
    try {
      mediapipeGraph.waitUntilGraphIdle();
    } catch (MediaPipeException e) {
      if (asyncErrorListener != null) {
        asyncErrorListener.onError(e);
      } else {
        // TODO: do not suppress exceptions here!
        Log.e(TAG, "Mediapipe error: ", e);
      }
    }
  }

  /**
   * Starts running the MediaPipe graph.
   * @throws MediaPipeException for any error status.
   */
  private void startGraph() {
    mediapipeGraph.startRunningGraph();
  }

  @Override
  public void onNewAudioData(ByteBuffer audioData, long timestampMicros, AudioFormat audioFormat) {
    Packet audioPacket = null;
    try {
      if (!started.getAndSet(true)) {
        startGraph();
      }

      if (audioFormat.getChannelCount() != numAudioChannels
          || audioFormat.getSampleRate() != audioSampleRate
          || audioFormat.getEncoding() != AUDIO_ENCODING) {
        Log.e(TAG, "Producer's AudioFormat doesn't match FrameProcessor's AudioFormat");
        return;
      }
      Preconditions.checkNotNull(audioInputStream);

      int numSamples = audioData.limit() / BYTES_PER_MONO_SAMPLE / numAudioChannels;
      audioPacket = packetCreator.createAudioPacket(audioData, numAudioChannels, numSamples);
      try {
        // addConsumablePacketToInputStream allows the graph to take exclusive ownership of the
        // packet, which may allow for more memory optimizations.
        mediapipeGraph.addConsumablePacketToInputStream(
            audioInputStream, audioPacket, timestampMicros);
        audioPacket = null;
      } catch (MediaPipeException e) {
        // TODO: do not suppress exceptions here!
        if (asyncErrorListener == null) {
          Log.e(TAG, "Mediapipe error: ", e);
        } else {
          throw e;
        }
      }
    } catch (RuntimeException e) {
      if (asyncErrorListener != null) {
        asyncErrorListener.onError(e);
      } else {
        throw e;
      }
    } finally {
      if (audioPacket != null) {
        // In case of error, addConsumablePacketToInputStream will not release the packet, so we
        // have to release it ourselves. (We could also re-try adding, but we don't).
        audioPacket.release();
      }
    }
  }

  public void addAudioConsumer(AudioDataConsumer consumer) {
    synchronized (this) {
      List<AudioDataConsumer> newConsumers = new ArrayList<>(audioConsumers);
      newConsumers.add(consumer);
      audioConsumers = newConsumers;
    }
  }

  public boolean removeAudioConsumer(AudioDataConsumer consumer) {
    boolean existed;
    synchronized (this) {
      List<AudioDataConsumer> newConsumers = new ArrayList<>(audioConsumers);
      existed = newConsumers.remove(consumer);
      audioConsumers = newConsumers;
    }
    return existed;
  }
}
