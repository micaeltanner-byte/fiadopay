package edu.ucsal.fiadopay.controller;

import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

@RestController
@RequestMapping("/fiadopay/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Autenticação de merchants e geração de tokens")
public class AuthController {

    private final MerchantRepository merchants;

    @Operation(
            summary = "Gerar token de acesso",
            description = "Verifica o client_id e client_secret de um merchant e retorna um token Bearer válido."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token gerado com sucesso",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TokenResponse.class),
                            examples = @ExampleObject(value = """
                    {
                      "access_token": "FAKE-123456",
                      "token_type": "Bearer",
                      "expires_in": 3600
                    }
                """)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Credenciais inválidas ou merchant inativo",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                    {
                      "status": 401,
                      "error": "Unauthorized",
                      "message": "Invalid credentials"
                    }
                """)
                    )
            )
    })
    @PostMapping("/token")
    public TokenResponse token(
            @RequestBody @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Credenciais do merchant para gerar o token",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = TokenRequest.class),
                            examples = @ExampleObject(value = """
                    {
                      "client_id": "loja_teste_123",
                      "client_secret": "minha_chave_secreta"
                    }
                """)
                    )
            )
            TokenRequest req
    ) {
        var merchant = merchants.findByClientId(req.client_id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (!merchant.getClientSecret().equals(req.client_secret())
                || merchant.getStatus() != Merchant.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        return new TokenResponse("FAKE-" + merchant.getId(), "Bearer", 3600);
    }
}
