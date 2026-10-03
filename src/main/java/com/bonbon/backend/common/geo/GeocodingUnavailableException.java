package com.bonbon.backend.common.geo;

import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** The geocoding provider timed out, failed, or our outbound quota is used up. Saved addresses keep working. */
public class GeocodingUnavailableException extends BusinessException {

    public GeocodingUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_UNAVAILABLE",
                "Tạm thời không tra cứu được địa chỉ. Vui lòng thử lại sau ít phút.");
    }
}
