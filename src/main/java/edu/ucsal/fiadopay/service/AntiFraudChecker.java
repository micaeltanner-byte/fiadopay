package edu.ucsal.fiadopay.service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import edu.ucsal.fiadopay.annotations.AntiFraud;
import edu.ucsal.fiadopay.controller.PaymentRequest;

@Component
public class AntiFraudChecker implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AntiFraudChecker.class);

    private final AntiFraudProperties props;
    private final ApplicationContext ctx;

    // combined map of ruleName -> threshold
    private final Map<String, Double> rules = new HashMap<>();

    public AntiFraudChecker(AntiFraudProperties props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // load rules from properties
        for (var r : props.getRules()){
            rules.put(r.getName(), r.getThreshold());
        }

        // scan bean types for @AntiFraud on methods WITHOUT instantiating beans (avoid circular deps)
        String[] beans = ctx.getBeanDefinitionNames();
        for (String b : beans) {
            try {
                Class<?> type = ctx.getType(b);
                if (type == null) continue;
                Method[] methods = type.getMethods();
                for (Method m : methods) {
                    var ann = m.getAnnotation(AntiFraud.class);
                    if (ann != null) {
                        rules.put(ann.name(), ann.threshold());
                        log.info("Registered AntiFraud rule from annotation: {} -> {}", ann.name(), ann.threshold());
                    }
                }
            } catch (Exception ex) {
                // ignore types we can't resolve
            }
        }
    }

    public void check(PaymentRequest req){
        if (req == null) return;
        BigDecimal amountBd = req.amount();
        if (amountBd == null) return;
        double amt = amountBd.doubleValue();

        for (var entry : rules.entrySet()){
            var name = entry.getKey();
            var threshold = entry.getValue();
            if (amt > threshold){
                // currently action is block
                log.warn("AntiFraud triggered: rule={}, amount={}, threshold={}", name, amt, threshold);
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "AntiFraud("+name+"): amount exceeds threshold");
            }
        }
    }
}
