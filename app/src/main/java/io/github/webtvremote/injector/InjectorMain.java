package io.github.webtvremote.injector;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Tiny input injection server. It is NOT run inside the app process: the app starts it
 * through ADB as the shell user, the same way scrcpy does:
 *
 *   CLASSPATH=/path/base.apk app_process / io.github.webtvremote.injector.InjectorMain PORT TOKEN
 *
 * Running as shell gives it INJECT_EVENTS, so keys are injected directly into the input
 * system without paying the start-up cost of the `input` command for every press.
 *
 * Protocol (one line per message, UTF-8): the first line must be TOKEN, then
 *   key CODE MODE   MODE = click | down | up | long
 *   text BASE64     types ASCII text using the virtual keyboard map
 *   ping
 * Every command is answered with "ok" or "err <reason>".
 */
public final class InjectorMain {

    private static final int INJECT_MODE_ASYNC = 0;

    private final Object inputManager;
    private final Method injectMethod;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private InjectorMain() throws Exception {
        Object im;
        try {
            // Android 14+
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            im = global.getDeclaredMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            im = Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null);
        }
        inputManager = im;
        injectMethod = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String token = args[1];
        InjectorMain injector = new InjectorMain();
        ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("READY " + port);
        System.out.flush();
        while (true) {
            Socket socket = server.accept();
            Thread t = new Thread(() -> injector.serve(socket, token), "client");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket, String token) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            if (!token.equals(in.readLine())) return;
            String line;
            while ((line = in.readLine()) != null) {
                String reply;
                try {
                    reply = handle(line.trim().split(" "));
                } catch (Exception e) {
                    reply = "err " + e.getClass().getSimpleName();
                }
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private String handle(String[] cmd) throws Exception {
        switch (cmd[0]) {
            case "ping":
                return "ok";
            case "key":
                return key(Integer.parseInt(cmd[1]), cmd.length > 2 ? cmd[2] : "click") ? "ok" : "err inject";
            case "text": {
                String text = new String(Base64.getDecoder().decode(cmd.length > 1 ? cmd[1] : ""), StandardCharsets.UTF_8);
                KeyEvent[] events = charMap.getEvents(text.toCharArray());
                if (events == null) return "err unmappable";
                for (KeyEvent e : events) {
                    if (!inject(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0))) return "err inject";
                }
                return "ok";
            }
            default:
                return "err unknown";
        }
    }

    private boolean key(int code, String mode) throws Exception {
        long down = SystemClock.uptimeMillis();
        switch (mode) {
            case "down":
                return inject(event(down, KeyEvent.ACTION_DOWN, code, 0, 0));
            case "up":
                return inject(event(down, KeyEvent.ACTION_UP, code, 0, 0));
            case "long":
                // Same sequence as `input keyevent --longpress`.
                if (!inject(event(down, KeyEvent.ACTION_DOWN, code, 0, 0))) return false;
                Thread.sleep(500);
                inject(event(down, KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS));
                return inject(event(down, KeyEvent.ACTION_UP, code, 0, 0));
            default:
                return inject(event(down, KeyEvent.ACTION_DOWN, code, 0, 0))
                        && inject(event(down, KeyEvent.ACTION_UP, code, 0, 0));
        }
    }

    private static KeyEvent event(long downTime, int action, int code, int repeat, int flags) {
        return new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags | KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);
    }

    private boolean inject(KeyEvent event) throws Exception {
        return (Boolean) injectMethod.invoke(inputManager, event, INJECT_MODE_ASYNC);
    }
}
