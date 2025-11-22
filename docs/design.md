# Design & Arquitetura — FiadoPay Simulator

Visão geral

- Processamento assíncrono: `PaymentService` delega trabalhos longos a um `ExecutorService` (`AsyncConfig`).
- Webhooks: persistidos em `WebhookDelivery` e entregues assincronamente com retries, HMAC e circuit-breaker simples.
- Anotações: `@AntiFraud` pode ser colocada em métodos para documentar regras; `AntiFraudChecker` combina essas anotações com regras declaradas em `application.yml`.

Componentes chave

- `AsyncConfig`: fornece `ExecutorService` e `ScheduledExecutorService` com nomes de threads para observabilidade.
- `PaymentService`: criação, processamento simulado e envio de webhooks. Mantém idempotência por `idempotencyKey`.
- `AntiFraudChecker` + `AntiFraudProperties`: regras carregadas por config e discovery via annotations.
- `PaymentHandler` + `CardPaymentHandler`: strategy para comportamento específico de métodos de pagamento.
- `WebhookDeliveryCircuitCircuitBreaker`: circuito simples por URL alvo para evitar spamming de sinks falhos.
- `DeliveryMetrics`: contadores básicos de tentativas/sucessos/falhas.

Configuração de antifraude

- Regras declaradas em `fiadopay.antifraud.rules` (lista) em `application.yml`.
- Regras anotadas: marque métodos com `@AntiFraud(name="RuleName", threshold=...)` para que sejam registradas automaticamente.

Retries e backoff

- Retries usam `ScheduledExecutorService` com backoff exponencial: `2^(attempts) * 1000ms`.
- Circuit-breaker tripa após N falhas (padrão 5) e aplica cooldown crescente.

Observabilidade

- Thread naming para identificar tasks no thread dump.
- `DeliveryMetrics` fornece contadores simples; para produção, use Micrometer.

Limitações conhecidas

- Circuit-breaker simples em memória (não distribuído). Para produção, considerar `resilience4j` ou similar.
- Regras anti-fraude simples; falta UI e persistência para regras dinâmicas.
- Cobertura de testes automáticos ainda não adicionada — recomendado adicionar unit/integration tests.
