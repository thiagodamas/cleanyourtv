package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the
 * Chromecast voice remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so
 * with the stock launcher disabled the button does nothing. Only KEYCODE_TV is consumed by default;
 * every other key passes through untouched.
 *
 * A theme can opt in to this launcher's own Inputs menu. {@code input_button_opens_menu} sends
 * KEYCODE_TV there. {@code input_button_scancodes} adds Linux scancodes, matched only from an IR
 * receiver, so a keyboard key with the same code still types. Both are off in config.xml. If the
 * Inputs pill would be hidden, those keys are not consumed.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** IR repeat fires the same code several times per press. One press opens the menu once. */
    private static final long OPEN_DEBOUNCE_MS = 400;
    private static long lastOpenMs;
    /** Reloaded when config.json changes, so a theme applies without a rebuild. Not read on every key. */
    private Config cached;
    private long cachedStamp = Long.MIN_VALUE;

    private Config config() {
        File dir = Config.dir(this);
        File file = dir != null ? new File(dir, Config.FILE_NAME) : null;
        long stamp = file != null && file.isFile() ? file.lastModified() : 0L;
        if (cached == null || stamp != cachedStamp) {
            cached = Config.load(this);
            cachedStamp = stamp;
        }
        return cached;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Config cfg = config();
        // Default, including while this service is enabled: the system switcher, and only KEYCODE_TV.
        if (event.getKeyCode() == KeyEvent.KEYCODE_TV && !cfg.inputButtonOpensMenu) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
            return true;
        }
        if (!opensLauncherMenu(event, cfg.inputButtonOpensMenu, cfg.inputButtonScancodes)) return false;
        // Nothing to show: leave the key alone instead of swallowing it.
        if (!menuAvailable(this, cfg)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openInputs(this);
        return true;
    }

    /**
     * KEYCODE_TV when the theme asks for this launcher's menu, or a configured scancode from an IR
     * receiver. A keyboard is not an IR receiver, so its keys still type.
     */
    static boolean opensLauncherMenu(KeyEvent event, boolean opensMenu, int[] scancodes) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_TV && opensMenu) return true;
        if (scancodes == null || scancodes.length == 0 || !fromIr(event)) return false;
        int scan = event.getScanCode();
        for (int code : scancodes) if (scan == code) return true;
        return false;
    }

    /** The AIWA source button arrives from "MStar Smart TV IR Receiver", not from a keyboard. */
    private static boolean fromIr(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) device = InputDevice.getDevice(event.getDeviceId());
        String name = device == null ? null : device.getName();
        return name != null && (name.contains("IR Receiver") || name.contains("MStar"));
    }

    /**
     * Same cases as the Inputs pill: a Live TV app, a source panel, or any passthrough input.
     * A hidden pill means the key must not be eaten.
     */
    static boolean menuAvailable(Context ctx, Config cfg) {
        if (liveTvLaunchable(ctx, cfg)) return true;
        if (cfg.sourcePanelService != null && !cfg.sourcePanelService.isEmpty()) return true;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isPassthroughInput()) return true;
        }
        return false;
    }

    /** Leanback entry, or the configured action when the package has no leanback activity. */
    private static boolean liveTvLaunchable(Context ctx, Config cfg) {
        if (cfg.liveTvPackage == null || cfg.liveTvPackage.isEmpty()) return false;
        PackageManager pm = ctx.getPackageManager();
        if (pm.getLeanbackLaunchIntentForPackage(cfg.liveTvPackage) != null) return true;
        if (cfg.liveTvAction == null || cfg.liveTvAction.isEmpty()) return false;
        Intent byAction = new Intent(cfg.liveTvAction).setPackage(cfg.liveTvPackage);
        return pm.resolveActivity(byAction, 0) != null;
    }

    static void openInputs(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - lastOpenMs < OPEN_DEBOUNCE_MS) return;
        lastOpenMs = now;
        context.startActivity(new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW_INPUTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
