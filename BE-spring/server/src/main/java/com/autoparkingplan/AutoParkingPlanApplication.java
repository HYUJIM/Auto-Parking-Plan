package com.autoparkingplan;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.autoparkingplan.server.cli.Cli;

/**
 * APP(Auto Parking Plan) 법규 서버 — 주소 기반 토지·법규 초기검토 및 주차장 배치 시뮬레이션 API (비로그인).
 *
 * <p>실행: {@code java -jar app.jar serve [--host 127.0.0.1] [--port 8000]}. 그 밖의 운영 명령은 {@link Cli}.
 */
@SpringBootApplication
public class AutoParkingPlanApplication {
    public static void main(String[] args) {
        int code = Cli.main(args);
        if (code != Cli.KEEP_RUNNING) {
            System.exit(code);
        }
    }
}
