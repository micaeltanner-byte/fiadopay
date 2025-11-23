package edu.ucsal.fiadopay.controller;

import edu.ucsal.fiadopay.domain.Merchant;
import edu.ucsal.fiadopay.repo.MerchantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@RestController
@RequestMapping("/fiadopay/admin/merchants")
@RequiredArgsConstructor
@Tag(name = "Merchant Admin", description = "Endpoints administrativos para gerenciamento de merchants")
public class MerchantAdminController {

    private final MerchantRepository merchants;

    @Operation(
            summary = "Criar um novo merchant",
            description = "Cria um merchant e gera automaticamente Client ID e Client Secret."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Merchant criado com sucesso",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Merchant.class),
                            examples = @ExampleObject(value = """
                    {
                      "id": "a32f22c3-3eaf-4d59-9c40-7da4b310cc55",
                      "name": "Loja Teste",
                      "webhookUrl": "https://minhaloja.com/webhook",
                      "clientId": "f3ab2f40-b4cd-4b56-81b3-13e3aee6a76a",
                      "clientSecret": "9f08c12be20f4e7ab622f5c0f69178d1",
                      "status": "ACTIVE"
                    }
                """)
                    )
            ),
            @ApiResponse(responseCode = "409", description = "Nome do merchant já existe"),
            @ApiResponse(responseCode = "400", description = "Erro na validação dos dados enviados")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Merchant create(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Dados para criação do merchant",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = MerchantCreateDTO.class),
                            examples = @ExampleObject(value = """
                        {
                          "name": "Loja Teste",
                          "webhookUrl": "https://minhaloja.com/webhook"
                        }
                    """)
                    )
            )
            MerchantCreateDTO dto
    ) {
        if (merchants.existsByName(dto.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Merchant name already exists");
        }

        var m = Merchant.builder()
                .name(dto.name())
                .webhookUrl(dto.webhookUrl())
                .clientId(UUID.randomUUID().toString())
                .clientSecret(UUID.randomUUID().toString().replace("-", ""))
                .status(Merchant.Status.ACTIVE)
                .build();

        return merchants.save(m);
    }
}
