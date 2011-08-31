/*
 * Copyright 2006-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.consol.citrus.ws.message.callback;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import javax.xml.soap.MimeHeader;
import javax.xml.soap.MimeHeaders;
import javax.xml.transform.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.Message;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.util.StringUtils;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.mime.Attachment;
import org.springframework.ws.soap.*;
import org.springframework.ws.soap.axiom.AxiomSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.xml.transform.StringResult;

import com.consol.citrus.util.FileUtils;
import com.consol.citrus.ws.message.CitrusSoapMessageHeaders;

/**
 * Receiver callback invoked by framework on response message. Callback fills an internal message representation with
 * the response information for further message processing.
 * 
 * @author Christoph Deppisch
 */
public class SoapResponseMessageCallback implements WebServiceMessageCallback {

    /**
     * Logger
     */
    private static Logger log = LoggerFactory.getLogger(SoapResponseMessageCallback.class);
    
    /** The response message built from WebService response message */
    private Message<String> response;

    /**
     * Callback method called with actual web service response message. Method constructs a Spring Integration
     * message from this web service message for further processing.
     */
    public void doWithMessage(WebServiceMessage responseMessage) throws IOException, TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        
        StringResult responsePayload = new StringResult();
        transformer.transform(responseMessage.getPayloadSource(), responsePayload);
        
        MessageBuilder<String> responseMessageBuilder = MessageBuilder.withPayload(responsePayload.toString());
        
        if (responseMessage instanceof SoapMessage) {
            SoapMessage soapMessage = (SoapMessage) responseMessage;
            
            handleSoapHeaders(soapMessage, responseMessageBuilder);
            handleAttachments(soapMessage, responseMessageBuilder);
            
            handleMimeHeaders(soapMessage, responseMessageBuilder);
        }
        
        // now set response for later access via getResponse():
        response = responseMessageBuilder.build();
    }
    
    /**
     * Adds attachments if present in soap web service message.
     * 
     * @param soapMessage the web service message.
     * @param messageBuilder the response message builder.
     * @throws IOException 
     */
    private void handleAttachments(SoapMessage soapMessage, MessageBuilder<String> messageBuilder) throws IOException {
        Iterator<?> attachments = soapMessage.getAttachments();

        while (attachments.hasNext()) {
            Attachment attachment = (Attachment)attachments.next();
            
            if (StringUtils.hasText(attachment.getContentId())) {
                String contentId = attachment.getContentId();
                
                if (contentId.startsWith("<")) {contentId = contentId.substring(1);}
                if (contentId.endsWith(">")) {contentId = contentId.substring(0, contentId.length()-1);}
                
                if (log.isDebugEnabled()) {
                    log.debug("SOAP message contains attachment with contentId '" + contentId + "'");
                }
                
                messageBuilder.setHeader(contentId, attachment);                        
                messageBuilder.setHeader(CitrusSoapMessageHeaders.CONTENT_ID, contentId);
                messageBuilder.setHeader(CitrusSoapMessageHeaders.CONTENT_TYPE, attachment.getContentType());
                messageBuilder.setHeader(CitrusSoapMessageHeaders.CONTENT, FileUtils.readToString(attachment.getInputStream()).trim());
                messageBuilder.setHeader(CitrusSoapMessageHeaders.CHARSET_NAME, "UTF-8"); // TODO map this dynamically
            } else {
                log.warn("Could not handle SOAP attachment with empty 'contentId'. Attachment is ignored in further processing");
            }
        }
    }

    /**
     * Reads all soap headers from web service message and 
     * adds them to message builder as normal headers. Also takes care of soap action header.
     * 
     * @param soapMessage the web service message.
     * @param messageBuilder the response message builder.
     */
    private void handleSoapHeaders(SoapMessage soapMessage, MessageBuilder<?> messageBuilder) {
        SoapHeader soapHeader = soapMessage.getSoapHeader();
        
        if (soapHeader != null) {
            Iterator<?> iter = soapHeader.examineAllHeaderElements();
            while (iter.hasNext()) {
                SoapHeaderElement headerEntry = (SoapHeaderElement) iter.next();
                messageBuilder.setHeader(headerEntry.getName().getLocalPart(), headerEntry.getText());
            }
        }
        
        if (StringUtils.hasText(soapMessage.getSoapAction())) {
            if (soapMessage.getSoapAction().equals("\"\"")) {
                messageBuilder.setHeader(CitrusSoapMessageHeaders.SOAP_ACTION, "");
            } else {
                if (soapMessage.getSoapAction().startsWith("\"") && soapMessage.getSoapAction().endsWith("\"")) {
                    messageBuilder.setHeader(CitrusSoapMessageHeaders.SOAP_ACTION, 
                            soapMessage.getSoapAction().substring(1, soapMessage.getSoapAction().length()-1));
                } else {
                    messageBuilder.setHeader(CitrusSoapMessageHeaders.SOAP_ACTION, soapMessage.getSoapAction());
                }
            }
        }
    }
    
    /**
     * Adds mime headers to constructed response message. This can be HTTP headers in case
     * of HTTP transport. Note: HTTP headers may have multiple values that are represented as 
     * comma delimited string value.
     * 
     * @param soapMessage the source SOAP message.
     * @param messageBuilder the message build constructing the result message. 
     */
    private void handleMimeHeaders(SoapMessage soapMessage, MessageBuilder<String> messageBuilder) {
        Map<String, String> mimeHeaders = new HashMap<String, String>();
        MimeHeaders messageMimeHeaders = null;
        
        // to get access to mime headers we need to get implementation specific here
        if (soapMessage instanceof SaajSoapMessage) {
            messageMimeHeaders = ((SaajSoapMessage)soapMessage).getSaajMessage().getMimeHeaders();
        } else if (soapMessage instanceof AxiomSoapMessage) {
            // we do not handle axiom message implementations as it is very difficult to get access to the mime headers there
            log.warn("Skip mime headers for AxiomSoapMessage - unsupported");
        } else {
            log.warn("Unsupported SOAP message implementation - skipping mime headers");
        }
        
        if (messageMimeHeaders != null) {
            Iterator<?> mimeHeaderIterator = messageMimeHeaders.getAllHeaders();
            while (mimeHeaderIterator.hasNext()) {
                MimeHeader mimeHeader = (MimeHeader)mimeHeaderIterator.next();
                // http headers can have multpile values so headers might occur several times in map
                if (mimeHeaders.containsKey(mimeHeader.getName())) {
                    // header is already present, so concat values to a single comma delimited string
                    String value = mimeHeaders.get(mimeHeader.getName());
                    value += ", " + mimeHeader.getValue();
                    mimeHeaders.put(mimeHeader.getName(), value);
                } else {
                    mimeHeaders.put(mimeHeader.getName(), mimeHeader.getValue());
                }
            }
            
            for (Entry<String, String> httpHeaderEntry : mimeHeaders.entrySet()) {
                messageBuilder.setHeader(httpHeaderEntry.getKey(), httpHeaderEntry.getValue());
            }
        }
    }

    /**
     * Gets the constructed Spring Integration response message object.
     * @return the response message.
     */
    public Message<String> getResponse() {
        return response;
    }
}
