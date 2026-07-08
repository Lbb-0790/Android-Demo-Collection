package com.test.mic;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;

/**
 * 完全按照 SoundRecorder 的方式录音：
 * MediaRecorder + AudioSource.MIC + AAC编码 + 3GPP输出
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MicTest";
    private static final int REQUEST_RECORD_AUDIO = 100;

    private Button btnStart, btnStop;
    private TextView tvStatus, tvLog;
    private MediaRecorder mRecorder;
    private File mSampleFile;
    private long mSampleStart;
    private volatile boolean isRecording = false;
    private Thread mAmpThread;  // 模拟VU表，用getMaxAmplitude检测数据

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopRecording();
            }
        });
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            appendLog("请求录音权限...");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
            return;
        }
        doStartRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("权限已授予");
                doStartRecording();
            } else {
                appendLog("ERROR: 权限被拒绝！");
                Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doStartRecording() {
        // 创建输出文件到应用私有目录，不需要 WRITE_EXTERNAL_STORAGE 权限
        mSampleFile = new File(getExternalCacheDir(),
                "mic_test_" + System.currentTimeMillis() + ".3gpp");
        appendLog("输出文件: " + mSampleFile.getAbsolutePath());

        try {
            // ===== 完全按照 SoundRecorder Recorder.java initAndStartMediaRecorder() =====

            // 1. 创建 MediaRecorder
            mRecorder = new MediaRecorder();
            appendLog("MediaRecorder 创建成功");

            // 2. 设置录音源 MIC（Recorder.java 第274行）
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            appendLog("setAudioSource(MIC) OK");

            // 3. 设置输出格式 THREE_GPP（Recorder.java 第275行）
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            appendLog("setOutputFormat(THREE_GPP) OK");

            // 4. 设置输出文件（Recorder.java 第276行）
            mRecorder.setOutputFile(mSampleFile.getAbsolutePath());
            appendLog("setOutputFile OK");

            // 5. 设置音频编码器 AAC（Recorder.java 第295行）
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            appendLog("setAudioEncoder(AAC) OK");

            // 6. 设置声道数 2=立体声（Recorder.java 第296行）
            mRecorder.setAudioChannels(2);
            appendLog("setAudioChannels(2 立体声) OK");

            // 7. 设置码率 128000（Recorder.java 第297行）
            mRecorder.setAudioEncodingBitRate(128000);
            appendLog("setAudioEncodingBitRate(128000) OK");

            // 8. 设置采样率 48000（Recorder.java 第298行）
            mRecorder.setAudioSamplingRate(48000);
            appendLog("setAudioSamplingRate(48000) OK");

            // 9. 设置错误监听（Recorder.java 第302行）
            mRecorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override
                public void onError(MediaRecorder mr, int what, int extra) {
                    appendLog("ERROR: MediaRecorder onError what=" + what + " extra=" + extra);
                }
            });

            // 10. prepare + start（Recorder.java 第304-305行）
            mRecorder.prepare();
            appendLog("prepare() OK");
            mRecorder.start();
            appendLog("start() OK");

        } catch (IOException e) {
            appendLog("ERROR: IOException: " + e.getMessage());
            releaseRecorder();
            return;
        } catch (RuntimeException e) {
            appendLog("ERROR: RuntimeException: " + e.getMessage());
            releaseRecorder();
            return;
        }

        mSampleStart = System.currentTimeMillis();
        isRecording = true;

        // 启动振幅检测线程（和 SoundRecorder VUMeter 一样用 getMaxAmplitude）
        mAmpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int lastAmp = 0;
                while (isRecording) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }

                    if (mRecorder != null) {
                        try {
                            int amp = mRecorder.getMaxAmplitude();
                            if (amp != lastAmp) {
                                lastAmp = amp;
                                final long elapsed = (System.currentTimeMillis() - mSampleStart) / 1000;
                                final int amplitude = amp;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvStatus.setText("状态: 录音中 " + elapsed + "s, 振幅=" + amplitude);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            // getMaxAmplitude 在录音停止后会抛异常，忽略
                        }
                    }
                }
            }
        });
        mAmpThread.start();

        tvStatus.setText("状态: 录音中...");
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        appendLog("===== 开始录音 (MediaRecorder + MIC + AAC + 48000 立体声) =====");
    }

    private void stopRecording() {
        isRecording = false;

        // 停止振幅检测线程
        try {
            if (mAmpThread != null) {
                mAmpThread.join(1000);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (mRecorder != null) {
            try {
                mRecorder.stop();
                appendLog("stop() OK");
            } catch (RuntimeException e) {
                appendLog("stop() 异常: " + e.getMessage());
            } finally {
                mRecorder.reset();
                mRecorder.release();
                mRecorder = null;
                appendLog("release() OK");
            }
        }

        long duration = (System.currentTimeMillis() - mSampleStart) / 1000;
        tvStatus.setText("状态: 已停止, 时长 " + duration + "s");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        appendLog("===== 录音结束 时长=" + duration + "s =====");
        appendLog("文件: " + (mSampleFile != null ? mSampleFile.getAbsolutePath() : "null"));
        appendLog("文件大小: " + (mSampleFile != null && mSampleFile.exists() ?
                mSampleFile.length() + " 字节" : "文件不存在!"));
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            try { mRecorder.release(); } catch (Exception e) {}
            mRecorder = null;
        }
    }

    private void appendLog(final String msg) {
        Log.d(TAG, msg);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String text = tvLog.getText().toString();
                tvLog.setText(text + "\n" + msg);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
}
