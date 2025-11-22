package edu.ucsal.fiadopay.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fiadopay.antifraud")
public class AntiFraudProperties {
    public static class Rule {
        private String name;
        private double threshold = 1000.0;
        private String action = "block"; // block, warn, review

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    private List<Rule> rules = new ArrayList<>();

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }
}
