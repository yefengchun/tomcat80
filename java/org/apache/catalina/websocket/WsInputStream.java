/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.websocket;

import java.io.IOException;

import org.apache.catalina.util.Conversions;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;

/**
 * This class is used to read WebSocket frames from the underlying socket and
 * makes the payload available for reading as an {@link InputStream}. It only
 * makes the number of bytes declared in the payload length available for
 * reading even if more bytes are available from the socket.
 */
public class WsInputStream extends java.io.InputStream {

    private UpgradeProcessor<?> processor;
    private WsFrameHeader wsFrameHeader;
    private long payloadLength = -1;
    private int[] mask = new int[4];


    private long remaining;
    private long readThisFragment;

    public WsInputStream(UpgradeProcessor<?> processor) throws IOException {
        this.processor = processor;

        processFrameHeader();
    }


    private void processFrameHeader() throws IOException {

        // TODO: Per frame extension handling is not currently supported.

        // TODO: Handle control frames between fragments

        int i = processor.read();
        this.wsFrameHeader = new WsFrameHeader(i);

        // Client data must be masked
        i = processor.read();
        if ((i & 0x80) == 0) {
            // TODO: StringManager / i18n
            throw new IOException("Client frame not masked");
        }

        payloadLength = i & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = new byte[2];
            processor.read(extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        } else if (payloadLength == 127) {
            byte[] extended = new byte[8];
            processor.read(extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        }
        remaining = payloadLength;

        for (int j = 0; j < mask.length; j++) {
            mask[j] = processor.read() & 0xFF;
        }

        readThisFragment = 0;
    }

    public WsFrameHeader getFrameHeader() {
        return wsFrameHeader;
    }

    public long getPayloadLength() {
        return payloadLength;
    }


    // ----------------------------------------------------- InputStream methods

    @Override
    public int read() throws IOException {
        while (remaining == 0 && !getFrameHeader().getFin()) {
            // Need more data - process next frame
            processFrameHeader();

            if (getFrameHeader().getOpCode() != Constants.OPCODE_CONTINUATION) {
                // TODO i18n
                throw new IOException("Not a continuation frame");
            }
        }

        if (remaining == 0) {
            return -1;
        }

        remaining--;
        readThisFragment++;

        int masked = processor.read();
        if(masked == -1) {
            return -1;
        }
        return masked ^ mask[(int) ((readThisFragment - 1) % 4)];
    }
}
