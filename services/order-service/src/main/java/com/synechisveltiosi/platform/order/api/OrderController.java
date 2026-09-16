package com.synechisveltiosi.platform.order.api;

import com.synechisveltiosi.platform.order.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;
import static com.synechisveltiosi.platform.order.application.OrderModels.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderWorkflows orders;
    private final DocumentWorkflows documents;
    public OrderController(OrderWorkflows orders, DocumentWorkflows documents) { this.orders = orders; this.documents = documents; }
    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateOrder input, HttpServletRequest request) {
        var result = orders.create(tenant(jwt), key, input, correlation(request));
        return ResponseEntity.created(URI.create("/api/v1/orders/" + result.resourceId()))
                .contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    @GetMapping("/{orderId}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) { return orders.get(tenant(jwt), orderId); }
    @PatchMapping("/{orderId}/status")
    public OrderView changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody ChangeStatus input, HttpServletRequest request) {
        return orders.changeStatus(tenant(jwt), orderId, input, correlation(request));
    }
    @PostMapping("/{orderId}/documents")
    public ResponseEntity<Registration> register(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RegisterDocument input) {
        var registration = documents.register(tenant(jwt), orderId, key, input);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + orderId + "/documents/" + registration.document().id())).body(registration);
    }
    @GetMapping("/{orderId}/documents/{documentId}")
    public DocumentView document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId, @PathVariable UUID documentId) {
        return DocumentView.of(orders.document(tenant(jwt), orderId, documentId));
    }
    @PostMapping("/{orderId}/documents/{documentId}/complete")
    public ResponseEntity<DocumentView> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @PathVariable UUID documentId, HttpServletRequest request) {
        return ResponseEntity.accepted().body(documents.complete(tenant(jwt), orderId, documentId, correlation(request)));
    }
    private UUID tenant(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
    private UUID correlation(HttpServletRequest request) { return (UUID) request.getAttribute(CorrelationFilter.ATTRIBUTE); }
}
