# FiadoPay Simulator

Pequeno simulador de gateway de pagamentos desenvolvido para entrega acadêmica.

**Contexto Escolhido**

- Objetivo: fornecer um backend didático que simule o comportamento de um gateway de pagamentos para avaliação acadêmica (autorizações, idempotência, parcelamento, webhooks).
- Motivação: demonstrar boas práticas de engenharia como processamento assíncrono, uso de annotations para metadados, patterns de projeto e tratamento de retries/resiliência.

**Decisões de Design**

- Arquitetura modular com serviços Spring Boot, H2 em memória para persistência e endpoints REST simples.
- Processamento assíncrono: tasks longas (processamento do pagamento, reentrega de webhook) são executadas fora da thread HTTP usando `ExecutorService` (bean `fiadoExecutor`) e agendadas com `ScheduledExecutorService` (`fiadoScheduler`).
- Anti-fraude híbrido: suportamos regras declaradas em `application.yml` e discovery de regras através de anotações `@AntiFraud` em métodos — ambos combinados no runtime.
- Observabilidade mínima: nomes de thread para identificar workers, logs informativos e contadores simples (`DeliveryMetrics`).
- Resiliência: retries com backoff exponencial e um circuito-breaker simples em memória por target para evitar spam a sinks indisponíveis.

**Anotações criadas e metadados**

- `@PaymentMethod(type="CARD")` — marca um handler ou classe associada a um método de pagamento. (arquivo: `src/main/java/edu/ucsal/fiadopay/annotations/PaymentMethod.java`)
- `@AntiFraud(name="HighAmount", threshold=1000.0)` — marca que um método possui uma regra anti-fraude; aceita `name` e `threshold`. (arquivo: `src/main/java/edu/ucsal/fiadopay/annotations/AntiFraud.java`)
- `@WebhookSink` — marca sinks/handlers de webhook para discovery futuro. (arquivo: `src/main/java/edu/ucsal/fiadopay/annotations/WebhookSink.java`)

Para cada annotation descreva os metadados que ela carrega — por exemplo `@AntiFraud` traz `name` e `threshold` (double). Essas annotations são destinadas a expressar política/metadata; a aplicação também aceita regras via `application.yml` para configuração por ambiente.

**Mecanismo de Reflexão (runtime)**

- Classe: `AntiFraudChecker` (`src/main/java/edu/ucsal/fiadopay/service/AntiFraudChecker.java`).
- O que faz:
  - lê `fiadopay.antifraud.rules` do `application.yml` (bind via `AntiFraudProperties`).
  - percorre todos os beans do `ApplicationContext` e inspeciona métodos à procura de `@AntiFraud` (reflection).
  - registra regras (nome -> threshold) em um mapa combinado.
  - no momento de criar um pagamento, `AntiFraudChecker.check(req)` é chamado e aplica todas as regras registradas; se um threshold é excedido a ação atual é bloquear a operação (lançar `ResponseStatusException`).

**Threads e execução assíncrona**

- `AsyncConfig` provê dois beans:
  - `ExecutorService fiadoExecutor()` — pool fixo, threads nomeadas `fiado-exec-*`.
  - `ScheduledExecutorService fiadoScheduler()` — scheduler para retries, threads nomeadas `fiado-sched-*`.
- Uso:
  - `PaymentService.createPayment(...)` persiste o pagamento e envia uma task assíncrona ao `fiadoExecutor` para processar e notificar via webhook.
  - Reentregas de webhook usam o `fiadoScheduler` para agendamento com backoff.

**Padrões de projeto aplicados**

- Strategy: `PaymentHandler` interface com implementações como `CardPaymentHandler` para encapsular lógica específica (ex.: cálculo de juros para cartão e parcelamento).
- Circuit Breaker (simples, in-memory): `WebhookDeliveryCircuitCircuitBreaker` per-target evita tentativas contínuas quando um sink falha repetidamente.
- Producer-Consumer: persistência de `WebhookDelivery` + workers que consomem e enviam.
- Annotation-Driven Discovery: `@AntiFraud` e demais anotations detectadas via reflection na inicialização.

**Limites conhecidos e recomendações**

- Circuit-breaker em memória — reiniciar a aplicação limpa o estado. Para produção, usar uma solução testada (`resilience4j`) e uma store externa se necessário.
- Anti-fraude: atualmente thresholds simples; não há UI, história de regras nem versionamento.
- HMAC: implementação simples; em produção usar práticas de rotação de segredos e bibliotecas bem testadas.
- Métricas: contadores em memória; recomendo Micrometer + Prometheus/Grafana para observabilidade real.
- Testes: adicionar unit/integration tests para cobrir idempotência, anti-fraud, handlers e fluxo de webhooks.

**Como rodar (PowerShell Windows)**

```powershell
./mvnw.cmd -DskipTests=true package
./mvnw.cmd spring-boot:run
# ou
.\start.ps1
```

**O que deve aparecer no terminal (evidências que você deve capturar — "prints")**
Capture screenshots/prints do terminal mostrando os seguintes trechos:

- Build bem-sucedido (trecho):

```
[INFO] --- maven-compiler-plugin:...:compile (...) ---
[INFO] BUILD SUCCESS
```

- Startup da aplicação e discovery de `@AntiFraud` (trecho):

```
2025-11-21 23:12:45.123  INFO 12345 --- [           main] o.s.b.SpringApplication : Starting FiadoPayApplication v1.0.0
2025-11-21 23:12:47.890  INFO 12345 --- [           main] edu.ucsal.fiadopay.service.AntiFraudChecker : Registered AntiFraud rule from annotation: HighAmount -> 1000.0
2025-11-21 23:12:48.456  INFO 12345 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port(s): 8080 (http)
2025-11-21 23:12:48.789  INFO 12345 --- [           main] o.s.b.SpringApplication : Started FiadoPayApplication in 3.4 seconds
```

- Fluxo de processamento + webhook (trecho):

```
-- SQL INSERT into PAYMENT (aparece com spring.jpa.show-sql=true)
-- After delay: SQL UPDATE PAYMENT -> status APPROVED/DECLINED
-- SQL INSERT into WEBHOOK_DELIVERY
INFO  Received webhook sink: eventType=payment.updated, signature=..., payload={...}
```

- Anti-fraude acionada (trecho):

```
WARN  AntiFraud triggered: rule=HighAmount, amount=1234.0, threshold=1000.0
HTTP/1.1 402 Payment Required
```

**Evidências (logs de exemplo incluidos)**

- Arquivo com trechos de logs já incluído em: `docs/evidences/logs.txt` — contém exemplos de build, startup e sink.

**Arquivos-chave do repositório**

- `src/main/java/edu/ucsal/fiadopay/service/PaymentService.java` — fluxo de pagamentos e integração com executor.
- `src/main/java/edu/ucsal/fiadopay/service/AntiFraudChecker.java` — mecanismo de reflection + config.
- `src/main/java/edu/ucsal/fiadopay/payment/PaymentHandler.java` e `CardPaymentHandler.java` — strategy.
- `src/main/java/edu/ucsal/fiadopay/service/WebhookDeliveryCircuitCircuitBreaker.java` — circuito simples.
- `start.ps1` — script para build + run no Windows.

---

# FiadoPay Simulator

Pequeno simulador de gateway de pagamentos para fins acadêmicos.

Conteúdo entregue

- Código-fonte em `src/main/java`.
- `pom.xml` (build Maven) e wrapper `mvnw.cmd`.
- `README.md` (este arquivo).
- `docs/` com design e instruções.
- `start.ps1` script para iniciar localmente no Windows.

Principais funcionalidades

- Processamento assíncrono de pagamentos via `ExecutorService` (`AsyncConfig`).
- Anotações: `@PaymentMethod`, `@AntiFraud`, `@WebhookSink` (marca/metadata).
- Anti-fraude: regras combinadas via `application.yml` (`fiadopay.antifraud.rules`) e discovery de `@AntiFraud` anotadas.
- Idempotência por `idempotencyKey`.
- Juros para pagamentos `CARD` parcelados (1%/mês) e strategy via `PaymentHandler`.
- Webhooks com assinatura HMAC, retries com exponential backoff e circuito simples por target.

Pré-requisitos

- Java 21+ JDK
- Maven (usando wrapper incluso `mvnw.cmd` no Windows)

Build e execução

No Windows PowerShell, buildar:

```powershell
./mvnw.cmd -DskipTests=true package
```

Rodar localmente:

```powershell
./mvnw.cmd spring-boot:run
```

ou usar o script fornecido:

```powershell
.\start.ps1
```

Configuração relevante (`src/main/resources/application.yml`)

- `fiadopay.webhook-secret`: segredo usado para calcular HMAC dos webhooks.
- `fiadopay.processing-delay-ms`: latência simulada no processamento.
- `fiadopay.failure-rate`: taxa de falha simulada.
- `fiadopay.antifraud.rules`: lista de regras anti-fraude (exemplo abaixo).

Exemplo `application.yml` (trecho):

```yaml
fiadopay:
  webhook-secret: ucsal-2025
  processing-delay-ms: 1500
  failure-rate: 0.15
  antifraud:
    rules:
      - name: HighAmount
        threshold: 1000.0
        action: block
      - name: VeryHigh
        threshold: 5000.0
        action: block
```

Testes manuais (exemplos curl)

- Criar pagamento (substitua `<merchantId>` pelo id do merchant):

```powershell
curl -X POST http://localhost:8080/payments -H "Authorization: Bearer FAKE-<merchantId>" -H "Content-Type: application/json" -d '{"method":"CARD","amount":150.00,"currency":"BRL","installments":3}'
```

- Consultar pagamento:

```powershell
curl http://localhost:8080/payments/{paymentId}
```

- Refund (exemplo):

```powershell
curl -X POST http://localhost:8080/payments/{paymentId}/refund -H "Authorization: Bearer FAKE-<merchantId>"
```

Receber webhooks localmente para testes

- O repositório inclui um `WebhookSinkController` que expõe `POST /sink` para testes locais.
- Configure o `webhookUrl` do merchant para `http://host.docker.internal:8080/sink` (quando rodar localmente) ou `http://localhost:8080/sink`.

Observações e recomendações

- A solução prioriza clareza pedagógica. Para produção: fortalecer HMAC, usar bibliotecas de resiliência (resilience4j), expor métricas via Micrometer/Prometheus, testes automatizados.

---

Criado para entrega acadêmica — ver `docs/design.md` para detalhes de arquitetura e anotações.

# FiadoPay Simulator (Spring Boot + H2)

Gateway de pagamento **FiadoPay** para a AVI/POOA.
Substitui PSPs reais com um backend em memória (H2).

## Rodar

```bash
./mvnw spring-boot:run
# ou
mvn spring-boot:run
```

H2 console: http://localhost:8080/h2  
Swagger UI: http://localhost:8080/swagger-ui.html

## Fluxo

1. **Cadastrar merchant**

```bash
curl -X POST http://localhost:8080/fiadopay/admin/merchants   -H "Content-Type: application/json"   -d '{"name":"MinhaLoja ADS","webhookUrl":"http://localhost:8081/webhooks/payments"}'
```

2. **Obter token**

```bash
curl -X POST http://localhost:8080/fiadopay/auth/token   -H "Content-Type: application/json"   -d '{"client_id":"<clientId>","client_secret":"<clientSecret>"}'
```

3. **Criar pagamento**

```bash
curl -X POST http://localhost:8080/fiadopay/gateway/payments   -H "Authorization: Bearer FAKE-<merchantId>"   -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000"   -H "Content-Type: application/json"   -d '{"method":"CARD","currency":"BRL","amount":250.50,"installments":12,"metadataOrderId":"ORD-123"}'
```

4. **Consultar pagamento**

```bash
curl http://localhost:8080/fiadopay/gateway/payments/<paymentId>
```
