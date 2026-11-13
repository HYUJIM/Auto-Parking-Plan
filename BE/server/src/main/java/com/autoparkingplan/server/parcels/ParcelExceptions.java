package com.autoparkingplan.server.parcels;

/** 필지 서비스 예외 (파이썬 AddressNotFound / ParcelNotFound / UpstreamUnavailable). */
public final class ParcelExceptions {
    private ParcelExceptions() {
    }

    public static class AddressNotFound extends RuntimeException {
        public AddressNotFound(String message) {
            super(message);
        }
    }

    public static class ParcelNotFound extends RuntimeException {
        public ParcelNotFound(String message) {
            super(message);
        }
    }

    public static class UpstreamUnavailable extends RuntimeException {
        private final Long retryAfterSeconds;

        public UpstreamUnavailable(String message) {
            this(message, null);
        }

        /** @param retryAfterSeconds 이 시간 뒤에 다시 시도하라는 안내 (모르면 null) */
        public UpstreamUnavailable(String message, Long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public Long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
