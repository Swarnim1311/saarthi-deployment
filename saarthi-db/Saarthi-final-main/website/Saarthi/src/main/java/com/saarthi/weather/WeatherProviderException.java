package com.saarthi.weather;

/** Thrown when the upstream weather API fails. Never silently replaced with synthetic values. */
public class WeatherProviderException extends RuntimeException {
    public WeatherProviderException(String message) {
        super(message);
    }

    public WeatherProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
