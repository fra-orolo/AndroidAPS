package info.nightscout.androidaps.plugins.pump.insight;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.pump.insight.activities.InsightAlertActivity;
import info.nightscout.androidaps.plugins.pump.insight.app_layer.remote_control.ConfirmAlertMessage;
import info.nightscout.androidaps.plugins.pump.insight.app_layer.remote_control.SnoozeAlertMessage;
import info.nightscout.androidaps.plugins.pump.insight.app_layer.status.GetActiveAlertMessage;
import info.nightscout.androidaps.plugins.pump.insight.connection_service.InsightConnectionService;
import info.nightscout.androidaps.plugins.pump.insight.descriptors.Alert;
import info.nightscout.androidaps.plugins.pump.insight.descriptors.AlertStatus;
import info.nightscout.androidaps.plugins.pump.insight.descriptors.AlertType;
import info.nightscout.androidaps.plugins.pump.insight.descriptors.InsightState;
import info.nightscout.androidaps.plugins.pump.insight.exceptions.InsightException;
import info.nightscout.androidaps.plugins.pump.insight.exceptions.app_layer_errors.AppLayerErrorException;
import info.nightscout.androidaps.plugins.pump.insight.utils.ExceptionTranslator;

public class InsightAlertService extends Service implements InsightConnectionService.StateCallback {

    private static Logger log = LoggerFactory.getLogger(L.PUMPCOMM);

    private LocalBinder localBinder = new LocalBinder();
    private boolean connectionRequested;
    private final Object $alertLock = new Object[0];
    private Alert alert;
    private Thread thread;
    private InsightAlertActivity alertActivity;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private boolean vibrating;
    private InsightConnectionService connectionService;
    private long ignoreTimestamp;
    private volatile Long alertStart;
    private AlertType ignoreType;
    private boolean awaitingActivity = false;
    private long lastSoundPlay = 0;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            connectionService = ((InsightConnectionService.LocalBinder) binder).getService();
            connectionService.registerStateCallback(InsightAlertService.this);
            onStateChanged(connectionService.getState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connectionService = null;
        }
    };

    private void retrieveRingtone() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        ringtone = RingtoneManager.getRingtone(this, uri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .setLegacyStreamType(AudioManager.STREAM_RING).build());
        } else ringtone.setStreamType(AudioManager.STREAM_RING);
    }

    public Alert getAlert() {
        synchronized ($alertLock) {
            return alert;
        }
    }
    public synchronized void deRegisterAlertActivity(InsightAlertActivity alertActivity) {
        if(this.alertActivity == alertActivity) {
            this.alertActivity = null;
            this.awaitingActivity = false;
            synchronized ($alertLock) {
                if (this.alert != null) {
                    ensureActivityLaunched(); // re-launch for Screen Rotate etc
                }
            }
        }
    }

    public synchronized void register(InsightAlertActivity alertActivity) {
        if (this.alertActivity != null && this.alertActivity != alertActivity) {
            ensureActivityFinished();
        }
        this.alertActivity = alertActivity;
        this.awaitingActivity = false;
    }

    public void ignore(AlertType alertType) {
        synchronized ($alertLock) {
            if (alertType == null) {
                ignoreTimestamp = 0;
                ignoreType = null;
            } else {
                ignoreTimestamp = System.currentTimeMillis();
                ignoreType = alertType;
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return localBinder;
    }

    @Override
    public void onCreate() {
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        bindService(new Intent(this, InsightConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.interrupt();
        unbindService(serviceConnection);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onStateChanged(InsightState state) {
        if (state == InsightState.CONNECTED) {
            thread = new Thread(this::queryActiveAlert);
            thread.start();

        } else if (thread != null) thread.interrupt();
    }

    public void activityShowingAlert(Alert alert) {
        synchronized ($alertLock) {
            if (alert.getAlertStatus() == AlertStatus.ACTIVE) {
                log.debug("Alert showing in activity "+alert.getAlertId());
                startOrEscalateAlert();
            } else {
                log.debug("confirmed Alert showing in activity "+alert);
                stopAlerting();
            }
        }
    }

    private void queryActiveAlert() {
        while (!Thread.currentThread().isInterrupted()) {
            long waitTime = 1000;
            try {
                Alert alert = connectionService.requestMessage(new GetActiveAlertMessage()).await().getAlert();
                if (Thread.currentThread().isInterrupted()) {
                    connectionService.withdrawConnectionRequest(thread);
                    break;
                }
                synchronized ($alertLock) {
                    if ((this.alert == null && alert != null)
                            || (this.alert != null && alert == null)
                            || (this.alert != null && alert != null && !this.alert.equals(alert))) {
                        if (this.alert != null && (alert == null || this.alert.getAlertId() != alert.getAlertId())) stopAlerting();
                        this.alert = alert;
                        if (alertActivity != null && alert != null)
                            new Handler(Looper.getMainLooper()).post(() -> alertActivity.update(alert));
                    }
                    if(alertStart != null && alert != null) {
                        startOrEscalateAlert();
                    }
                    if (alert == null) {
                        stopAlerting();
                        if (connectionRequested) {
                            connectionService.withdrawConnectionRequest(this);
                            connectionRequested = false;
                        }
                        ensureActivityFinished();
                    } else if (!(alert.getAlertType() == ignoreType && System.currentTimeMillis() - ignoreTimestamp < 10000))  {
                        if (!connectionRequested) {
                            connectionService.requestConnection(this);
                            connectionRequested = true;
                        }
                        if (alertActivity == null) {
                            ensureActivityLaunched();
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                connectionService.withdrawConnectionRequest(thread);
                break;
            } catch (AppLayerErrorException e) {
                log.info("Exception while fetching alert: " + e.getClass().getCanonicalName() + " (" + e.getErrorCode() + ")");
            } catch (InsightException e) {
                log.info("Exception while fetching alert: " + e.getClass().getSimpleName());
            } catch (Exception e) {
                log.error("Exception while fetching alert ", e);
            }
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (connectionRequested) {
            connectionService.withdrawConnectionRequest(thread);
            connectionRequested = false;
        }
        ensureActivityFinished();
        stopAlerting();
        thread = null;
    }

    private synchronized void ensureActivityLaunched() {
        if(this.awaitingActivity == false) {
            this.awaitingActivity = true;
            Intent intent = new Intent(InsightAlertService.this, InsightAlertActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            new Handler(Looper.getMainLooper()).post(() -> startActivity(intent));
        }
    }

    private void ensureActivityFinished() {
        if (alertActivity != null) {
            new Handler(Looper.getMainLooper()).post(() -> alertActivity.finish());
        }
    }

    private void startOrEscalateAlert() {
        log.debug("startAlert");
        long now = System.currentTimeMillis();
        synchronized ($alertLock) {
           if (alertStart == null) {
               log.debug("startAlert remember "+now);
               this.alertStart = now;
               this.lastSoundPlay = 0;
           }
        }
        final AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = AudioManager.RINGER_MODE_NORMAL;
        if(am != null) {
            ringerMode = am.getRingerMode();
        }
        boolean shouldVibrate = false;
        boolean shouldMakeNoise = false;
        boolean snoozed = false;
        boolean overTime = false;
        if (alertStart != null) {
            snoozed = (alert != null && alert.getAlertStatus() == AlertStatus.SNOOZED);
            overTime = (alertStart + 300000) < now;
            if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                if ((alertStart + 6000 < now) && ! snoozed) {
                    shouldVibrate = true;
                    log.debug("shouldVibrate");
                }
                if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    if ((alertStart + 30000 < now) && ! snoozed) {
                        shouldMakeNoise = true;
                        log.debug("shouldMakeNoise");
                    }
                }
            }
        }
        if (!vibrating && shouldVibrate) {
            log.debug("vibrate");
            vibrator.vibrate(new long[] {0, // pause
                    1200, // vib
                    600,  // pause
                    900, // vib
                    600,  // pause
                    900, // vib
                    (30000 - ((1200+600)*2+1200)) // long pause until repeat
            }, 0);
            vibrating = true;
        }
        if ((ringtone == null || !ringtone.isPlaying()) && shouldMakeNoise) {
            retrieveRingtone();
            if((lastSoundPlay + 60000) < now) {
                log.debug("play sound");
                lastSoundPlay = now;
                ringtone.play();
            }
        }
        if(vibrating && (overTime || snoozed)) {
            vibrator.cancel();
            vibrating = false;
        }
        if(ringtone != null && ringtone.isPlaying() && (overTime || snoozed)) {
            ringtone.stop();
        }
    }

    private void stopAlerting() {
        log.debug("stop alert");
        synchronized ($alertLock) {
            this.alertStart = null;
        }
        if (vibrating) {
            vibrator.cancel();
            vibrating = false;
        }
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }

    public void mute() {
        new Thread(() -> {
            try {
                SnoozeAlertMessage snoozeAlertMessage = new SnoozeAlertMessage();
                snoozeAlertMessage.setAlertID(alert.getAlertId());
                connectionService.requestMessage(snoozeAlertMessage).await();
            } catch (AppLayerErrorException e) {
                log.info("Exception while muting alert: " + e.getClass().getCanonicalName() + " (" + e.getErrorCode() + ")");
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            } catch (InsightException e) {
                log.info("Exception while muting alert: " + e.getClass().getSimpleName());
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            } catch (Exception e) {
                log.error("Exception while muting alert", e);
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            }
        }).start();
    }

    public void confirm() {
        new Thread(() -> {
            try {
                ConfirmAlertMessage confirmAlertMessage = new ConfirmAlertMessage();
                confirmAlertMessage.setAlertID(alert.getAlertId());
                connectionService.requestMessage(confirmAlertMessage).await();
            } catch (AppLayerErrorException e) {
                log.info("Exception while confirming alert: " + e.getClass().getCanonicalName() + " (" + e.getErrorCode() + ")");
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            } catch (InsightException e) {
                log.info("Exception while confirming alert: " + e.getClass().getSimpleName());
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            } catch (Exception e) {
                log.error("Exception while confirming alert", e);
                ExceptionTranslator.makeToast(InsightAlertService.this, e);
            }
        }).start();
    }

    public class LocalBinder extends Binder {
        public InsightAlertService getService() {
            return InsightAlertService.this;
        }
    }
}
