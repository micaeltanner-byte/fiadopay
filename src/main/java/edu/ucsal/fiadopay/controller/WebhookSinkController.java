package edu.ucsal.fiadopay.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookSinkController {
    private static final Logger log = LoggerFactory.getLogger(WebhookSinkController.class);

    @PostMapping("/sink")
    public ResponseEntity<String> receive(@RequestBody String body,
                                          @RequestHeader(value = "X-Signature", required = false) String signature,
                                          @RequestHeader(value = "X-Event-Type", required = false) String eventType){
        log.info("Received webhook sink: eventType={}, signature={}, payload={}", eventType, signature, body);
        return ResponseEntity.ok("ok");
    }
}
