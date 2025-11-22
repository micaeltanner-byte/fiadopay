package edu.ucsal.fiadopay.payment;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;

public interface PaymentHandler {
    String type();
    void apply(Payment p, PaymentRequest req);
}
