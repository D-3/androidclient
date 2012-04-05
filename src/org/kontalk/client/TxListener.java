package org.kontalk.client;

import com.google.protobuf.MessageLite;


/**
 * Listener interface for client transactions.
 * @author Daniele Ricci
 */
public interface TxListener {

    /**
     * Called when a pack has been received from the server.
     * @return true to prevent the listener from being called the next time.
     */
    public boolean tx(ClientConnection connection, String txId, MessageLite pack);

}