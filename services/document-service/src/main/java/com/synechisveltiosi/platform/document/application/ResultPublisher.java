package com.synechisveltiosi.platform.document.application;

import com.synechisveltiosi.platform.eventcontracts.Events;

public interface ResultPublisher {
    /**
     * Returns only after broker acceptance; an exception must prevent HTTP acknowledgment.
     */
    void publish(Events.Envelope event) throws Exception;
}
