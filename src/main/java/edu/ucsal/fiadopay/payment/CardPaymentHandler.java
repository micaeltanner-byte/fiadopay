package edu.ucsal.fiadopay.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import edu.ucsal.fiadopay.controller.PaymentRequest;
import edu.ucsal.fiadopay.domain.Payment;

@Component
public class CardPaymentHandler implements PaymentHandler {

    @Override
    public String type() { return "CARD"; }

    @Override
    public void apply(Payment p, PaymentRequest req) {
        Double interest = null;
        BigDecimal total = req.amount();
        if (req.installments()!=null && req.installments()>1){
            interest = 1.0; // 1%/mês
            var base = new BigDecimal("1.01");
            var factor = base.pow(req.installments());
            total = req.amount().multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        p.setMonthlyInterest(interest);
        p.setTotalWithInterest(total);
        p.setInstallments(req.installments()==null?1:req.installments());
    }
}
