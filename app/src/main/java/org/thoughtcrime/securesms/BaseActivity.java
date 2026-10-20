package org.thoughtcrime.securesms;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.Lifecycle;

import org.signal.core.util.ConfigurationUtil;
import org.signal.core.util.ServiceUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.biometric.BiometricDialogFragment;
import org.thoughtcrime.securesms.screenlock.ScreenLockAuthentication;
import org.thoughtcrime.securesms.screenlock.ScreenLockPinDialogFragment;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.AppStartup;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.WindowUtil;
import org.thoughtcrime.securesms.util.dynamiclanguage.DynamicLanguageContextWrapper;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Base class for all activities. The vast majority of activities shouldn't extend this directly.
 * Instead, they should extend {@link PassphraseRequiredActivity} so they're protected by
 * screen lock.
 */
public abstract class BaseActivity extends AppCompatActivity {
  private static final String TAG = Log.tag(BaseActivity.class);

  private boolean lockScreenState;
  private boolean screenLockPromptLaunched;
  private long screenLockPromptGeneration;
  private long screenLockPromptRequestId;
  private boolean screenLockDecoyExiting;
  private boolean topResumed;
  private boolean screenLockPromptScheduled;
  private final Runnable screenLockPrompt = () -> {
    screenLockPromptScheduled = false;
    if (!topResumed || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) ||
        isFinishing() || isDestroyed() || screenLockDecoyExiting || !useScreenLock() ||
        getSupportFragmentManager().isStateSaved() || ScreenLockController.isAccessAllowed() ||
        !ScreenLockController.isAuthenticationReady()) return;
    lockScreenState = true;
    showScreenLockPrompt(true);
  };
  private final ArrayDeque<Runnable> pendingScreenLockActions = new ArrayDeque<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    AppStartup.getInstance().onCriticalRenderEventStart();
    ScreenLockController.onActivityCreating(this);
    logEvent("onCreate()");
    super.onCreate(savedInstanceState);
    lockScreenState = ScreenLockController.getLockScreenAtStart();
    blockRecentAppsScreenshot(ScreenLockController.getAutoLock());
    AppStartup.getInstance().onCriticalRenderEventEnd();
  }

  @Override
  protected void onResume() {
    if (useScreenLock()) ScreenLockController.onActivityResuming();
    super.onResume();
    WindowUtil.initializeScreenshotSecurity(this, getWindow());
  }

  @Override
  protected void onPostResume() {
    super.onPostResume();

    boolean screenLocked = ScreenLockController.getLockScreenAtStart();

    if (!screenLocked && lockScreenState) {
      logEvent("onPostResume: screen no longer locked");
      BiometricDialogFragment.cancelAuthentication(this);
      ScreenLockPinDialogFragment.cancelAuthentication(this);
      screenLockPromptLaunched = false;
      onPostResume(false);
    } else {
      onPostResume(screenLocked);
    }

    if (Build.VERSION.SDK_INT < 29) {
      onTopResumedActivityChanged(true);
    }
    scheduleScreenLockPrompt();
  }

  @Override
  public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
    topResumed = isTopResumedActivity;
    scheduleScreenLockPrompt();
  }

  /** Window focus is not a reliable trigger: locked windows are deliberately not focusable. */
  private void scheduleScreenLockPrompt() {
    if (!topResumed || screenLockPromptScheduled || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) ||
        isFinishing() || isDestroyed() || screenLockDecoyExiting || !useScreenLock() ||
        getSupportFragmentManager().isStateSaved() || ScreenLockController.isAccessAllowed() ||
        !ScreenLockController.isAuthenticationReady()) return;
    screenLockPromptScheduled = true;
    // Fragment lifecycle callbacks can still be executing a transaction here.
    getWindow().getDecorView().post(screenLockPrompt);
  }

  protected void onPostResume(boolean screenLocked) {
    this.lockScreenState = screenLocked || !ScreenLockController.isAccessAllowed();

    if (!lockScreenState) {
      ScreenLockController.revealProtectedWindow(getWindow());
      ScreenLockController.unBlankScreen();
      if (getWindow() != null) {
        onWindowAttributesChanged(getWindow().getAttributes());
      }
      while (!pendingScreenLockActions.isEmpty() && ScreenLockController.isAccessAllowed()) {
        pendingScreenLockActions.removeFirst().run();
      }
    }
  }

  public final void runWhenScreenUnlocked(Runnable action) {
    if (ScreenLockController.isAccessAllowed()) action.run();
    else pendingScreenLockActions.addLast(action);
  }

  protected final void clearPendingScreenLockActions() {
    pendingScreenLockActions.clear();
  }

  public final void onScreenLockDecoyExit() {
    screenLockDecoyExiting = true;
    onPrepareScreenLockDecoyExit();
    clearPendingScreenLockActions();
    ScreenLockController.hideProtectedWindow(getWindow());
    ScreenLockPinDialogFragment.cancelAuthentication(this);
    BiometricDialogFragment.cancelAuthentication(this);
  }

  protected final boolean isScreenLockDecoyExiting() {
    return screenLockDecoyExiting;
  }

  protected void onPrepareScreenLockDecoyExit() {}

  /** Authentication/initialization screens may expose only guarded support actions. */
  protected final void authenticateScreenLockAction(Runnable action) {
    if (ScreenLockController.isAccessAllowed()) { action.run(); return; }
    final long generation = ScreenLockController.authenticationGeneration();
    ScreenLockAuthentication.authenticate(this, new BiometricDialogFragment.Listener() {
      @Override public boolean onSuccess() {
        if (ScreenLockController.completeAuthentication(generation)) action.run();
        return true;
      }
      @Override public boolean onCancel(boolean fromUser) {
        if (fromUser) onAuthenticationCancel();
        return true;
      }
      @Override public boolean onError(@NonNull CharSequence message) {
        Toast.makeText(BaseActivity.this, message, Toast.LENGTH_LONG).show();
        onAuthenticationCancel();
        return true;
      }
      @Override public boolean onNotEnrolled(@NonNull CharSequence message) { return onError(message); }
    }, true);
  }

  protected void onAuthenticationCancel() {
    if (!moveTaskToBack(true)) {
      logError("Failed to move to the back of the activity stack");
      finishAndRemoveTask();
    }
  }

  public boolean useScreenLock() {
    return true;
  }

  public void onScreenLockStateChanged() {
    if (isFinishing() || isDestroyed()) return;
    blockRecentAppsScreenshot(ScreenLockController.getAutoLock());
    WindowUtil.initializeScreenshotSecurity(this, getWindow());
    if (ScreenLockController.isAccessAllowed()) {
      screenLockPromptLaunched = false;
      onPostResume(false);
      ScreenLockPinDialogFragment.cancelAuthentication(this, false);
      BiometricDialogFragment.cancelAuthentication(this);
    } else if (useScreenLock()) {
      lockScreenState = true;
      ScreenLockController.hideProtectedWindow(getWindow());
      ScreenLockController.blankScreen();
      onWindowAttributesChanged(getWindow().getAttributes());
      scheduleScreenLockPrompt();
    }
  }

  protected void refreshScreenLockProtection() {
    if (!useScreenLock()) {
      ScreenLockController.revealExemptWindow(getWindow());
      ScreenLockPinDialogFragment.cancelAuthentication(this);
      BiometricDialogFragment.cancelAuthentication(this);
      screenLockPromptLaunched = false;
    } else {
      ScreenLockController.hideWhenLocked(getWindow());
      lockScreenState = ScreenLockController.shouldLockScreenAtStart();
      if (lockScreenState) ScreenLockController.blankScreen();
      else onPostResume(false);
    }
    onWindowAttributesChanged(getWindow().getAttributes());
    scheduleScreenLockPrompt();
  }

  public void blockRecentAppsScreenshot(boolean block) {
    if (Build.VERSION.SDK_INT >= 33) {
      setRecentsScreenshotEnabled(!block);
    }
  }

  @Override
  public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
    int noInputFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    if (lockScreenState && useScreenLock()) {
      params.flags |= noInputFlags;
    } else {
      params.flags &= ~noInputFlags;
    }
    super.onWindowAttributesChanged(params);
  }

  @Override
  public void onAttachedToWindow() {
    boolean alwaysVisible = !useScreenLock();
    if (alwaysVisible) {
      logEvent("onAttachedToWindow: window excluded from screen lock");
      ScreenLockController.showWhenLocked(getWindow());
    } else {
      ScreenLockController.hideWhenLocked(getWindow());
    }

    if (lockScreenState) {
      if (useScreenLock()) ScreenLockController.hideProtectedWindow(getWindow());
      ScreenLockController.blankScreen();
    }
  }

  private void showScreenLockPrompt(boolean fullScreen) {
    if (screenLockDecoyExiting || ScreenLockController.isAccessAllowed()) return;
    final long generation = ScreenLockController.authenticationGeneration();
    if (screenLockPromptLaunched && screenLockPromptGeneration == generation) return;

    logEvent("Prompting for screen lock: fullScreen=" + fullScreen);

    final long requestId = ++screenLockPromptRequestId;
    screenLockPromptGeneration = generation;
    screenLockPromptLaunched = true;
    ScreenLockAuthentication.authenticateForUnlock(
        this,
        new BiometricDialogFragment.Listener() {
          @Override
          public boolean onSuccess() {
            if (requestId != screenLockPromptRequestId) return true;
            screenLockPromptLaunched = false;
            if (ScreenLockController.completeAuthentication(generation) || !ScreenLockController.getLockScreenAtStart()) {
              onPostResume(false);
            }
            return true;
          }

          @Override
          public boolean onCancel(boolean fromUser) {
            if (requestId != screenLockPromptRequestId) return true;
            screenLockPromptLaunched = false;
            if (!ScreenLockController.getLockScreenAtStart()) {
              logEvent("Screen already unlocked by another activity. Ignore the cancel event.");
              onPostResume(false);
              return true;
            }
            if (fromUser) {
              logEvent("Authentication canceled from user");
              onAuthenticationCancel();
            } else {
              scheduleScreenLockPrompt();
            }
            return false;
          }

          @Override
          public boolean onError(@NonNull CharSequence errString) {
            if (requestId != screenLockPromptRequestId) return true;
            screenLockPromptLaunched = false;
            Toast.makeText(BaseActivity.this, errString, Toast.LENGTH_LONG).show();
            finishAndRemoveTask();
            return false;
          }

          @Override
          public boolean onNotEnrolled(@NonNull CharSequence errString) {
            if (requestId != screenLockPromptRequestId) return true;
            screenLockPromptLaunched = false;
            Toast.makeText(BaseActivity.this, errString, Toast.LENGTH_LONG).show();
            onAuthenticationCancel();
            return false;
          }
        }, fullScreen
    );
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && useScreenLock() && ScreenLockController.getLockScreenAtStart() && !screenLockPromptLaunched && !isFinishing()) {
      scheduleScreenLockPrompt();
    }
  }

  @Override
  protected void onPause() {
    topResumed = false;
    getWindow().getDecorView().removeCallbacks(screenLockPrompt);
    screenLockPromptScheduled = false;
    super.onPause();
  }

  @Override
  protected void onStart() {
    logEvent("onStart()");
    ScreenLockController.onActivityStarting(this);
    super.onStart();

    lockScreenState = useScreenLock() ? ScreenLockController.shouldLockScreenAtStart() : ScreenLockController.getLockScreenAtStart();
    if (lockScreenState) {
      logEvent("onStart: screen locked");
      if (useScreenLock()) ScreenLockController.hideProtectedWindow(getWindow());
      ScreenLockController.blankScreen();
    }
  }

  @Override
  protected void onStop() {
    logEvent("onStop()");
    ScreenLockController.onActivityStopping(this);
    screenLockPromptLaunched = false;
    pendingScreenLockActions.clear();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    logEvent("onDestroy()");
    super.onDestroy();
  }

  private boolean hasShowWhenLockedWindow() {
    return (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0;
  }

  protected void setExcludeFromRecents(boolean exclude) {
    ActivityManager.AppTask task = getTask();
    if (task != null) {
      task.setExcludeFromRecents(exclude);
    }
  }

  private @Nullable ActivityManager.AppTask getTask() {
    int taskId = getTaskId();

    List<ActivityManager.AppTask> tasks = ServiceUtil.getActivityManager(this).getAppTasks();
    for (ActivityManager.AppTask task : tasks) {
      if (task.getTaskInfo().id == taskId) {
        return task;
      }
    }
    return null;
  }

  @Override
  protected void attachBaseContext(@NonNull Context newBase) {
    super.attachBaseContext(newBase);

    Configuration configuration      = new Configuration(newBase.getResources().getConfiguration());
    int           appCompatNightMode = getDelegate().getLocalNightMode() != AppCompatDelegate.MODE_NIGHT_UNSPECIFIED ? getDelegate().getLocalNightMode()
                                                                                                                     : AppCompatDelegate.getDefaultNightMode();

    configuration.uiMode      = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mapNightModeToConfigurationUiMode(newBase, appCompatNightMode);
    configuration.orientation = Configuration.ORIENTATION_UNDEFINED;

    applyOverrideConfiguration(configuration);
  }

  @Override
  public void applyOverrideConfiguration(@NonNull Configuration overrideConfiguration) {
    DynamicLanguageContextWrapper.prepareOverrideConfiguration(this, overrideConfiguration);
    super.applyOverrideConfiguration(overrideConfiguration);
  }

  private void logEvent(@NonNull String event) {
    Log.d(TAG, "[" + Log.tag(getClass()) + "] " + event);
  }

  private void logError(@NonNull String event) {
    Log.e(TAG, "[" + Log.tag(getClass()) + "] " + event);
  }

  public final @NonNull ActionBar requireSupportActionBar() {
    return Objects.requireNonNull(getSupportActionBar());
  }

  private static int mapNightModeToConfigurationUiMode(@NonNull Context context, @AppCompatDelegate.NightMode int appCompatNightMode) {
    if (appCompatNightMode == AppCompatDelegate.MODE_NIGHT_YES) {
      return Configuration.UI_MODE_NIGHT_YES;
    } else if (appCompatNightMode == AppCompatDelegate.MODE_NIGHT_NO) {
      return Configuration.UI_MODE_NIGHT_NO;
    }
    return ConfigurationUtil.getNightModeConfiguration(context.getApplicationContext());
  }
}
