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

package com.consol.citrus.ws;

import java.util.*;
import java.util.Map.Entry;

import javax.xml.namespace.QName;
import javax.xml.soap.MimeHeader;
import javax.xml.soap.MimeHeaders;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.Message;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.mime.Attachment;
import org.springframework.ws.server.endpoint.MessageEndpoint;
import org.springframework.ws.soap.*;
import org.springframework.ws.soap.axiom.AxiomSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinitionEditor;
import org.springframework.ws.soap.soap11.Soap11Body;
import org.springframework.ws.soap.soap12.Soap12Body;
import org.springframework.ws.soap.soap12.Soap12Fault;
import org.springframework.xml.namespace.QNameUtils;
import org.springframework.xml.transform.StringResult;
import org.springframework.xml.transform.StringSource;
import org.w3c.dom.Document;

import com.consol.citrus.adapter.handler.EmptyResponseProducingMessageHandler;
import com.consol.citrus.exceptions.CitrusRuntimeException;
import com.consol.citrus.message.CitrusMessageHeaders;
import com.consol.citrus.message.MessageHandler;
import com.consol.citrus.util.MessageUtils;
import com.consol.citrus.ws.message.CitrusSoapMessageHeaders;

/**
 * SpringWS {@link MessageEndpoint} implementation. Endpoint will delegate message processing to 
 * a {@link MessageHandler} implementation.
 * 
 * @author Christoph Deppisch
 */
public class WebServiceEndpoint implements MessageEndpoint {

    /** MessageHandler handling incoming requests and providing proper responses */
    private MessageHandler messageHandler = new EmptyResponseProducingMessageHandler();
    
    /** Default namespace for all SOAP header entries */
    private String defaultNamespaceUri;
    
    /** Default prefix for all SOAP header entries */
    private String defaultPrefix = "";
    
    /** Include mime headers (HTTP headers) into request which is passed to the message handler */
    private boolean handleMimeHeaders = false;
    
    /**
     * Logger
     */
    private static Logger log = LoggerFactory.getLogger(WebServiceEndpoint.class);
    
    /** JMS headers begin with this prefix */
    private static final String DEFAULT_JMS_HEADER_PREFIX = "JMS";

    /**
     * @see org.springframework.ws.server.endpoint.MessageEndpoint#invoke(org.springframework.ws.context.MessageContext)
     * @throws CitrusRuntimeException
     */
    public void invoke(final MessageContext messageContext) throws Exception {
        Assert.notNull(messageContext.getRequest(), "Request must not be null - unable to send message");
        
        //build request message for message handler
        Message<String> requestMessage = buildRequestMessage(messageContext.getRequest(), messageContext);
        
        log.info("Received SOAP request:\n" + requestMessage.toString());
        
        //delegate request processing to message handler
        Message<?> replyMessage = messageHandler.handleMessage(requestMessage);
        
        if (replyMessage != null && replyMessage.getPayload() != null) {
            log.info("Sending SOAP response:\n" + replyMessage.toString());
            
            SoapMessage response = (SoapMessage)messageContext.getResponse();
            
            //add soap fault or normal soap body to response
            if (replyMessage.getHeaders().containsKey(CitrusSoapMessageHeaders.SOAP_FAULT)) {
                addSoapFault(response, replyMessage);
            } else {
                addSoapBody(response, replyMessage);
            }
            
            addSoapHeaders(response, replyMessage);
            addMimeHeaders(response, replyMessage);
        } else {
            log.info("No reply message from message handler '" + messageHandler + "'");
            log.warn("No SOAP response for calling client");
        }
    }
    
    /**
     * Transform incoming {@link WebServiceMessage} into a proper {@link Message} instance.
     * Specific SOAP message parts are translated to message headers with special names (e.g. SOAP attachments).
     * See {@link CitrusSoapMessageHeaders} for details.
     * 
     * @param requestMessage the web service request message.
     * @param messageContext the message context.
     * @return the internal request message representation.
     * @throws TransformerException 
     */
    private Message<String> buildRequestMessage(WebServiceMessage requestMessage, MessageContext messageContext) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        
        StringResult requestPayload = new StringResult();
        transformer.transform(requestMessage.getPayloadSource(), requestPayload);
        
        MessageBuilder<String> requestMessageBuilder = MessageBuilder.withPayload(requestPayload.toString());
        
        handleMessageProperties(messageContext, requestMessageBuilder);
        
        if (requestMessage instanceof SoapMessage) {
            SoapMessage soapMessage = (SoapMessage) requestMessage;
            
            handleSoapHeaders(soapMessage, requestMessageBuilder);
            handleAttachments(soapMessage, requestMessageBuilder);
            
            // take care of mime headers in message
            if (handleMimeHeaders) {
                handleMimeHeaders(soapMessage, requestMessageBuilder);
            }
        }
        
        return requestMessageBuilder.build();
    }

    /**
     * Adds mime headers outside of SOAP envelope. Header entries that go to this header section 
     * must have internal http header prefix defined in {@link CitrusSoapMessageHeaders}.
     * @param response the soap response message.
     * @param replyMessage the internal reply message.
     */
    private void addMimeHeaders(SoapMessage response, Message<?> replyMessage) {
        for (Entry<String, Object> headerEntry : replyMessage.getHeaders().entrySet()) {
            if (headerEntry.getKey().toLowerCase().startsWith(CitrusSoapMessageHeaders.HTTP_PREFIX)) {
                String headerName = headerEntry.getKey().substring(CitrusSoapMessageHeaders.HTTP_PREFIX.length());
                
                if (response instanceof SaajSoapMessage) {
                    SaajSoapMessage saajSoapMessage = (SaajSoapMessage) response;
                    MimeHeaders headers = saajSoapMessage.getSaajMessage().getMimeHeaders();
                    headers.setHeader(headerName, headerEntry.getValue().toString());
                } else if (response instanceof AxiomSoapMessage) {
                    log.warn("Unable to set mime message header '" + headerName + "' on AxiomSoapMessage - unsupported");
                } else {
                    log.warn("Unsupported SOAP message implementation - unable to set mime message header '" + headerName + "'");
                }
            }
        }
    }

    /**
     * Add message payload as SOAP body element to the SOAP response.
     * @param response
     * @param replyMessage
     */
    private void addSoapBody(SoapMessage response, Message<?> replyMessage) throws TransformerException {
        Source responseSource = getPayloadAsSource(replyMessage.getPayload());
        
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        
        transformer.transform(responseSource, response.getPayloadResult());
    }
    
    /**
     * Translates message headers to SOAP headers in response.
     * @param response
     * @param replyMessage
     */
    private void addSoapHeaders(SoapMessage response, Message<?> replyMessage) throws TransformerException {
        for (Entry<String, Object> headerEntry : replyMessage.getHeaders().entrySet()) {
            if (MessageUtils.isSpringInternalHeader(headerEntry.getKey()) || 
                    headerEntry.getKey().startsWith(DEFAULT_JMS_HEADER_PREFIX)) {
                continue;
            }
            
            if (headerEntry.getKey().equalsIgnoreCase(CitrusSoapMessageHeaders.SOAP_ACTION)) {
                response.setSoapAction(headerEntry.getValue().toString());
            } else if (headerEntry.getKey().equalsIgnoreCase(CitrusMessageHeaders.HEADER_CONTENT)) {
                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                Transformer transformer = transformerFactory.newTransformer();
                
                transformer.transform(new StringSource(headerEntry.getValue().toString()), 
                        response.getSoapHeader().getResult());
            } else if (headerEntry.getKey().startsWith(CitrusMessageHeaders.PREFIX)) {
                continue; //leave out Citrus internal header entries
            } else {
                SoapHeaderElement headerElement;
                if (QNameUtils.validateQName(headerEntry.getKey())) {
                    QName qname = QNameUtils.parseQNameString(headerEntry.getKey());
                    
                    if (StringUtils.hasText(qname.getNamespaceURI())) {
                        headerElement = response.getSoapHeader().addHeaderElement(qname);
                    } else {
                        headerElement = response.getSoapHeader().addHeaderElement(getDefaultQName(headerEntry.getKey()));
                    }
                } else {
                    throw new SoapHeaderException("Failed to add SOAP header '" + headerEntry.getKey() + "', " +
                            "because of invalid QName");
                }
                
                headerElement.setText(headerEntry.getValue().toString());
            }
        }
    }

    /**
     * Adds attachments if present in soap web service message.
     * 
     * @param soapMessage the web service message.
     * @param messageBuilder the response message builder.
     * 
     */
    private void handleAttachments(SoapMessage soapMessage, MessageBuilder<String> messageBuilder) {
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
     * Adds all message properties from web service request message to message builder 
     * as normal header entries.
     * 
     * @param messageContext the web service request message context.
     * @param messageBuilder the request message builder.
     */
    private void handleMessageProperties(MessageContext messageContext, MessageBuilder<String> messageBuilder) {
        String[] propertyNames = messageContext.getPropertyNames();
        if (propertyNames != null) {
            for (String propertyName : propertyNames) {
                messageBuilder.setHeader(propertyName, messageContext.getProperty(propertyName));
            }
        }
    }

    /**
     * Adds mime headers to constructed request message. This can be HTTP headers in case
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
     * Adds a SOAP fault to the SOAP response body. The SOAP fault is declared
     * as QName string in the response message's header (see {@link CitrusSoapMessageHeaders})
     * 
     * @param response
     * @param replyMessage
     */
    private void addSoapFault(SoapMessage response, Message<?> replyMessage) throws TransformerException {
        SoapFaultDefinitionEditor definitionEditor = new SoapFaultDefinitionEditor();
        definitionEditor.setAsText(replyMessage.getHeaders().get(CitrusSoapMessageHeaders.SOAP_FAULT).toString());
        
        SoapFaultDefinition definition = (SoapFaultDefinition)definitionEditor.getValue();
        SoapBody soapBody = response.getSoapBody();
        SoapFault soapFault = null;
        
        if (SoapFaultDefinition.SERVER.equals(definition.getFaultCode()) ||
                SoapFaultDefinition.RECEIVER.equals(definition.getFaultCode())) {
            soapFault = soapBody.addServerOrReceiverFault(definition.getFaultStringOrReason(), 
                    definition.getLocale());
        } else if (SoapFaultDefinition.CLIENT.equals(definition.getFaultCode()) ||
                SoapFaultDefinition.SENDER.equals(definition.getFaultCode())) {
            soapFault = soapBody.addClientOrSenderFault(definition.getFaultStringOrReason(), 
                    definition.getLocale());
        } else if (soapBody instanceof Soap11Body) {
            Soap11Body soap11Body = (Soap11Body) soapBody;
            soapFault = soap11Body.addFault(definition.getFaultCode(), 
                    definition.getFaultStringOrReason(), 
                    definition.getLocale());
        } else if (soapBody instanceof Soap12Body) {
            Soap12Body soap12Body = (Soap12Body) soapBody;
            Soap12Fault soap12Fault =
                    (Soap12Fault) soap12Body.addServerOrReceiverFault(definition.getFaultStringOrReason(), 
                            definition.getLocale());
            soap12Fault.addFaultSubcode(definition.getFaultCode());
            
            soapFault = soap12Fault;
        } else {
                throw new CitrusRuntimeException("Found unsupported SOAP implementation. Use SOAP 1.1 or SOAP 1.2.");
        }
        
        if (replyMessage.getPayload() instanceof String && 
                StringUtils.hasText(replyMessage.getPayload().toString())) {
            SoapFaultDetail faultDetail = soapFault.addFaultDetail();
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            
            transformer.transform(getPayloadAsSource(replyMessage.getPayload()), faultDetail.getResult());
        }
    }
    
    /**
     * Get the message payload object as {@link Source}, supported payload types are
     * {@link Source}, {@link Document} and {@link String}.
     * @param replyPayload payload object
     * @return {@link Source} representation of the payload
     */
    private Source getPayloadAsSource(Object replyPayload) {
        if (replyPayload instanceof Source) {
            return (Source) replyPayload;
        } else if (replyPayload instanceof Document) {
            return new DOMSource((Document) replyPayload);
        } else if (replyPayload instanceof String && StringUtils.hasText(replyPayload.toString())) {
            return new StringSource((String) replyPayload);
        } else {
            throw new CitrusRuntimeException("Unknown type for reply message payload (" + replyPayload.getClass().getName() + ") " +
                    "Supported types are " + 
                    "'" + Source.class.getName() + "', " +
                    "'" + Document.class.getName() + "'" + 
                    ", or 'java.lang.String'");
        }
    }

    /**
     * Get the default QName from local part.
     * @param localPart
     * @return
     */
    private QName getDefaultQName(String localPart) {
        if (StringUtils.hasText(defaultNamespaceUri)) {
            return QNameUtils.createQName(defaultNamespaceUri, localPart, defaultPrefix);
        } else {
            throw new SoapHeaderException("Failed to add SOAP header '" + localPart + "', " +
            		"because neither valid QName nor default namespace-uri is set!");
        }
    }

    /**
     * Set the message handler.
     * @param messageHandler the messageHandler to set
     */
    public void setMessageHandler(MessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    /**
     * Set the default namespace used in SOAP response headers.
     * @param defaultNamespaceUri the defaultNamespaceUri to set
     */
    public void setDefaultNamespaceUri(String defaultNamespaceUri) {
        this.defaultNamespaceUri = defaultNamespaceUri;
    }

    /**
     * Set the default namespace prefix used in SOAP response headers.
     * @param defaultPrefix the defaultPrefix to set
     */
    public void setDefaultPrefix(String defaultPrefix) {
        this.defaultPrefix = defaultPrefix;
    }

    /**
     * Enable mime headers in request message which is passed to message handler.
     * @param handleMimeHeaders the handleMimeHeaders to set
     */
    public void setHandleMimeHeaders(boolean handleMimeHeaders) {
        this.handleMimeHeaders = handleMimeHeaders;
    }
}