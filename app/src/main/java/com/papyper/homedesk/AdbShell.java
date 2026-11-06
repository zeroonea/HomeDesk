package com.papyper.homedesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Runs shell commands with ADB-shell rights by connecting to this tablet's own Wireless debugging, the same way
 * `adb` on a PC does (libadb-android). HomeDesk needs this for settings Android hides from apps, e.g. touchpad_* in
 * Settings.System: the settings provider only lets system, root and shell change non-public keys.
 *
 * Pair once (Wireless debugging › Pair device with pairing code, see AdbPairing). After that a command works
 * whenever Wireless debugging is on. HomeDesk's ADB key lives in no-backup storage, so it never leaves the tablet.
 */
final class AdbShell {

    /** Wireless debugging is off (or Wi-Fi is off): its connect service can't be found. */
    static final class WirelessDebuggingOffException extends IOException {
        WirelessDebuggingOffException() {
            super("Wireless debugging is off");
        }
    }

    /** This tablet doesn't know HomeDesk's key (never paired, or the pairing was removed). */
    static final class NotPairedException extends IOException {
        NotPairedException() {
            super("HomeDesk isn't paired with Wireless debugging");
        }
    }

    private static final String PREFS = "adb";
    private static final String KEY_PAIRED = "paired";
    private static final long FIND_CONNECT_MS = 6000, FIND_PAIRING_MS = 8000;
    /** Whole operation limits. libadb has no timeout on opening a stream: a dead connection waits forever. */
    private static final long RUN_TIMEOUT_MS = 20_000, PAIR_TIMEOUT_MS = 25_000, BUSY_TIMEOUT_MS = 30_000;

    /** One operation at a time; tryLock so a stuck one can't block every later call. */
    private static final ReentrantLock busy = new ReentrantLock();
    /** Runs the blocking libadb calls, so the caller can give up on them (daemon threads, nothing keeps them). */
    private static final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "adb-shell");
        t.setDaemon(true);
        return t;
    });
    /** Last Wireless debugging address that worked (its port changes when Wireless debugging is turned on). */
    private static volatile InetSocketAddress lastConnect;

    private AdbShell() {}

    static boolean isPaired(Context c) {
        return prefs(c).getBoolean(KEY_PAIRED, false);
    }

    private static void setPaired(Context c, boolean paired) {
        prefs(c).edit().putBoolean(KEY_PAIRED, paired).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Pairs with Wireless debugging using the 6-digit code. Its "Pair device with pairing code" dialog must be open
     * (the pairing service only runs while it shows). Call off the main thread.
     */
    static void pair(Context c, String code) throws Exception {
        String digits = code.replaceAll("\\D", "");
        if (digits.length() != 6)
            throw new IOException("The pairing code has 6 digits");
        Context app = c.getApplicationContext();
        limited(PAIR_TIMEOUT_MS, "Pairing didn't finish. Open Pair device with pairing code again.", () -> {
            InetSocketAddress at = find(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING, FIND_PAIRING_MS);
            if (at == null)
                throw new IOException("Couldn't find the pairing code dialog. Keep Wireless debugging › "
                        + "Pair device with pairing code open, then enter the code it shows.");
            new Manager(app).pair(at.getHostString(), at.getPort(), digits);
            return null;
        });
        setPaired(c, true);
    }

    /**
     * Runs a shell command (arguments are quoted) and returns what it printed. Throws when the command fails,
     * WirelessDebuggingOffException / NotPairedException when it can't connect. Call off the main thread.
     */
    static String run(Context c, String... args) throws Exception {
        StringBuilder cmd = new StringBuilder();
        for (String a : args)
            cmd.append(cmd.length() == 0 ? "" : " ").append(quote(a));
        cmd.append("; echo __rc=$?");
        Context app = c.getApplicationContext();
        String out = limited(RUN_TIMEOUT_MS, "Wireless debugging didn't answer. Try again, or turn Wireless "
                + "debugging off and on.", () -> exec(app, cmd.toString()));
        int at = out.lastIndexOf("__rc=");
        if (at < 0)
            throw new IOException("No answer from the shell");
        int rc;
        try {
            rc = Integer.parseInt(out.substring(at + 5).trim());
        } catch (NumberFormatException e) {
            rc = -1;
        }
        out = out.substring(0, at);
        if (rc != 0)
            throw new IOException(out.trim().isEmpty() ? "exit code " + rc : out.trim());
        return out;
    }

    /**
     * Runs task on a worker thread, one operation at a time, and gives up after timeoutMs. A task that is stuck
     * inside libadb is left behind on its daemon thread; it holds only its own Manager (each operation makes a new
     * one), so later operations still work.
     */
    private static <T> T limited(long timeoutMs, String timeoutMessage, Callable<T> task) throws Exception {
        if (!busy.tryLock(BUSY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            throw new IOException("Still busy with the previous change. Try again.");
        try {
            Future<T> f = worker.submit(task);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                lastConnect = null;
                throw new IOException(timeoutMessage);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
            }
        } finally {
            busy.unlock();
        }
    }

    /** Connects (fresh connection each time, so a connection Android dropped can't hang it), runs, disconnects. */
    private static String exec(Context c, String command) throws Exception {
        Manager m = new Manager(c);
        try {
            connect(c, m);
            try (AdbStream s = m.openStream("shell:" + command); InputStream in = s.openInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) >= 0)
                    buf.write(b, 0, n);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            try {
                m.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    private static void connect(Context c, Manager m) throws Exception {
        InetSocketAddress at = lastConnect;
        if (at != null) {
            try {
                if (m.connect(at.getHostString(), at.getPort()) || m.isConnected())
                    return;
            } catch (AdbPairingRequiredException e) {
                setPaired(c, false);
                throw new NotPairedException();
            } catch (IOException e) {
                // port changed (Wireless debugging was turned off and on): look it up again
            }
            m.disconnect();
            lastConnect = null;
        }
        at = find(c, AdbMdns.SERVICE_TYPE_TLS_CONNECT, FIND_CONNECT_MS);
        if (at == null)
            throw new WirelessDebuggingOffException();
        try {
            if (!m.connect(at.getHostString(), at.getPort()) && !m.isConnected())
                throw new IOException("Couldn't connect to Wireless debugging");
        } catch (AdbPairingRequiredException e) {
            setPaired(c, false);
            throw new NotPairedException();
        }
        lastConnect = at;
    }

    /** Finds this tablet's ADB service of the given mDNS type, or null after the timeout. */
    private static InetSocketAddress find(Context c, String type, long timeoutMs) throws InterruptedException {
        AtomicReference<InetSocketAddress> found = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(c.getApplicationContext(), type, (InetAddress host, int port) -> {
            if (host != null && port > 0) {
                found.set(new InetSocketAddress(host, port));
                latch.countDown();
            }
        });
        mdns.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            mdns.stop();
        }
        return found.get();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** HomeDesk's ADB identity: an RSA key and a self-signed certificate, made on first use. */
    private static final class Manager extends AbsAdbConnectionManager {
        private final PrivateKey privateKey;
        private final Certificate certificate;

        Manager(Context c) throws Exception {
            setApi(Build.VERSION.SDK_INT);
            setTimeout(10, TimeUnit.SECONDS);
            File dir = new File(c.getNoBackupFilesDir(), "adb");
            File keyFile = new File(dir, "private.key"), certFile = new File(dir, "cert.der");
            if (keyFile.exists() && certFile.exists()) {
                privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
                return;
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair pair = gen.generateKeyPair();
            PublicKey publicKey = pair.getPublic();
            privateKey = pair.getPrivate();

            String algorithm = "SHA512withRSA";
            X500Name name = new X500Name("CN=HomeDesk");
            Date notBefore = new Date();
            Date notAfter = new Date(notBefore.getTime() + 30L * 365 * 24 * 3600 * 1000);
            CertificateExtensions extensions = new CertificateExtensions();
            extensions.set("SubjectKeyIdentifier",
                    new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new SecureRandom().nextInt() & Integer.MAX_VALUE));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
            info.set("subject", new CertificateSubjectName(name));
            info.set("key", new CertificateX509Key(publicKey));
            info.set("validity", new CertificateValidity(notBefore, notAfter));
            info.set("issuer", new CertificateIssuerName(name));
            info.set("extensions", extensions);
            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(privateKey, algorithm);
            certificate = cert;

            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Can't create " + dir);
            Files.write(keyFile.toPath(), privateKey.getEncoded());
            Files.write(certFile.toPath(), certificate.getEncoded());
        }

        @Override
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        protected String getDeviceName() {
            return "HomeDesk";
        }
    }
}
