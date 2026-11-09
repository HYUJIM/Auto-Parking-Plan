package com.autoparkingplan.server.tools.e2e;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 검증 대상 운영 CLI: {@code java -jar app.jar <명령> …} (작업 폴더 = 프로젝트 루트, 파이썬판 {@code app_cmd / start_api / start_cli}).
 *
 * @param java 자바 실행 파일 (이 프로세스의 것)
 * @param jar  app.jar 경로
 * @param root 프로젝트 루트 (data/ 가 있는 곳 — 자식의 작업 폴더)
 */
record AppJar(String java, Path jar, Path root) {
    List<String> cmd(String... args) {
        List<String> c = new ArrayList<>(List.of(java, "-jar", jar.toString()));
        c.addAll(List.of(args));
        return c;
    }

    /** API 서버: {@code serve --port N --host 127.0.0.1}. */
    Process startApi(int port, Map<String, String> env, Path log) {
        return Procs.start(cmd("serve", "--port", String.valueOf(port), "--host", "127.0.0.1"), env, log, root);
    }

    /** 임의의 CLI 명령을 백그라운드로 (worker, serve --port N …). */
    Process startCli(Map<String, String> env, Path log, String... args) {
        return Procs.start(cmd(args), env, log, root);
    }

    /** 끝날 때까지 기다리는 CLI 명령 (bootstrap, check-config). */
    Procs.Completed run(Map<String, String> env, double timeoutS, String... args) {
        return Procs.run(cmd(args), root, env, timeoutS);
    }
}
