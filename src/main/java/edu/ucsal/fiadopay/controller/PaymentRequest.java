package edu.ucsal.fiadopay.controller;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record PaymentRequest(

        @Schema(example = "CARD")
        @NotBlank @Pattern(regexp = "(?i)CARD|PIX|DEBIT|BOLETO")
        String method,

        @Schema(example = "BRL")
        @NotBlank
        String currency,

        @Schema(example = "150.75")
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @Schema(example = "3")
        @Min(1) @Max(12)
        Integer installments,

        @Schema(example = "PEDIDO-123")
        @Size(max = 255)
        String metadataOrderId

) {}
