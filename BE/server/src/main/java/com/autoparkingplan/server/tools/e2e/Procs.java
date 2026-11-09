package com.autoparkingplan.server.tools.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 자식 프로세스·포트·기동 대기 도우미 (파이썬판 {@code free_port / wait_http / start / stop / subprocess.run}).
 *
 * <p>윈도우·맥·리눅스 공통: 셸을 거치지 않고 {@link ProcessBuilder} 로 실행하며, 출력은 파이썬판과 같은 로그 파일(stdout+stderr 합침)로 보낸다.
 */
final class Procs {
    private Procs() {
    }

    /** 127.0.0.1 의 빈 포트 하나. */
    static int freePort() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 응답(5xx 가 아닌 상태 코드)이 올 때까지 0.3초 간격으로 기다린다. 시간 안에 안 뜨면 예외. */
    static void waitHttp(String url, double timeoutS) {
        long deadline = System.nanoTime() + (long) (timeoutS * 1_000_000_000L);
        while (System.nanoTime() < deadline) {
            try {
                if (Http.getUrl(url, 2).status() < 500) {
                    return;
                }
            } catch (IOException | UncheckedIOException e) {
                // 아직 안 떴다 (연결 거부·시간 초과)
            }
            sleep(0.3);
        }
        throw new IllegalStateException("서버가 뜨지 않음: " + url);
    }

    static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기가 중단됐습니다", e);
        }
    }

    /** 백그라운드 프로세스 시작: stdout+stderr 를 로그 파일(새로 씀)로, 환경은 주어진 것으로 통째로 바꾼다. */
    static Process start(List<String> cmd, Map<String, String> env, Path log, Path cwd) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd.toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        setEnv(pb, env);
        try {
            Process p = pb.start();
            p.getOutputStream().close(); // stdin 은 쓰지 않는다
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 종료 요청(SIGTERM / TerminateProcess) → 10초 대기 → 강제 종료. 이미 끝났으면 아무것도 안 한다. */
    static void stop(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** {@code subprocess.run(..., capture_output=True, text=True, encoding="utf-8")} 결과. */
    record Completed(int returncode, String stdout, String stderr) {
    }

    /** 끝날 때까지 기다리며 stdout·stderr 를 따로 모은다. 시간을 넘기면 강제 종료 후 예외. */
    static Completed run(List<String> cmd, Path cwd, Map<String, String> env, double timeoutS) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toAbsolutePath().toFile());
        setEnv(pb, env);
        Process p;
        try {
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Drain out = new Drain(p.getInputStream());
        Drain err = new Drain(p.getErrorStream());
        out.start();
        err.start();
        try {
            if (!p.waitFor((long) (timeoutS * 1000), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor();
                throw new IllegalStateException("명령이 " + timeoutS + "초 안에 끝나지 않았습니다: " + String.join(" ", cmd));
            }
            out.join();
            err.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기가 중단됐습니다", e);
        }
        return new Completed(p.exitValue(), out.text(), err.text());
    }

    private static void setEnv(ProcessBuilder pb, Map<String, String> env) {
        Map<String, String> e = pb.environment();
        e.clear();
        e.putAll(env);
    }

    /** 스트림을 끝까지 읽는 스레드 (파이프가 차서 자식이 멈추지 않도록 stdout·stderr 를 동시에 읽는다). */
    private static final class Drain extends Thread {
        private final InputStream in;
        private byte[] data = new byte[0];

        Drain(InputStream in) {
            super("e2e-drain");
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (in) {
                data = in.readAllBytes();
            } catch (IOException e) {
                // 자식이 강제 종료되면 파이프가 끊긴다 — 읽은 데까지만
            }
        }

        /** 파이썬 text=True 처럼 UTF-8 로 읽고 줄바꿈을 \n 으로 통일한다. */
        String text() {
            return new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        }
    }
}
