package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.order.domain.DocumentStatus;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

import static com.synechisveltiosi.platform.order.application.OrderModels.*;

/**
 * Storage calls run outside SQL transactions. The final queue transaction rechecks state under an order lock.
 */
@Service
public class DocumentWorkflows {
    private final OrderWorkflows orders;
    private final DocumentStorage storage;
    private final Clock clock;

    public DocumentWorkflows(OrderWorkflows orders, DocumentStorage storage, Clock clock) {
        this.orders = orders;
        this.storage = storage;
        this.clock = clock;
    }

    public Registration register(UUID tenant, UUID order, String key, RegisterDocument input) {
        var id = orders.register(tenant, order, key, input);
        var document = orders.document(tenant, order, id);
        if (document.status() != DocumentStatus.AWAITING_UPLOAD)
            return new Registration(DocumentView.of(document), null);
        if (!clock.instant().isBefore(document.uploadExpiresAt()))
            throw ApiFailure.conflict("Upload registration has expired");
        var authorization = storage.authorize(document.bucket(), document.objectName(), document.declaredContentType(),
                document.uploadExpiresAt(), VerifiedUpload.MAX_SIZE_BYTES);
        return new Registration(DocumentView.of(document), authorization);
    }

    public DocumentView complete(UUID tenant, UUID order, UUID id, UUID correlation) {
        var document = orders.document(tenant, order, id);
        if (OrderWorkflows.hasProcessingAttempt(document)) return DocumentView.of(document);
        if (document.status() != DocumentStatus.AWAITING_UPLOAD || !clock.instant().isBefore(document.uploadExpiresAt()))
            throw ApiFailure.conflict("Document is not awaiting upload or its upload window has expired");
        var upload = storage.inspect(document.bucket(), document.objectName());
        if (!document.bucket().equals(upload.object().bucket()) || !document.objectName().equals(upload.object().objectName()))
            throw new ApiFailure(502, "Document storage returned inconsistent metadata");
        return orders.complete(tenant, order, id, upload, correlation);
    }
}
