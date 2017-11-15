/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.support.json;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.integration.mapping.BytesMessageMapper;
import org.springframework.integration.support.MutableMessage;
import org.springframework.integration.support.MutableMessageHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.PatternMatchUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * For outbound messages, uses a message-aware Jackson object mapper to render the message
 * as JSON. For messages with {@code byte[]} payloads, if rendered as JSON, Jackson
 * performs Base64 conversion on the bytes. If the {@link #setRawBytes(boolean) rawBytes}
 * property is true (default), the result has the form
 * &lt;headersLen&gt;&lt;headers&gt;&lt;payloadLen&gt;&lt;payload&gt;; with the headers
 * rendered in JSON and the payload unchanged.
 * <p>
 * By default, all headers are included; you can provide simple patterns to specify a
 * subset of headers.
 * <p>
 * If neither expected format is detected, or an error occurs during conversion, the
 * payload of the message is the original {@code byte[]}.
 * <p>
 * <b>IMPORTANT</b>
 * <p>
 * The default object mapper will only deserialize classes in certain packages.
 *
 * <pre class=code>
 *	"java.util",
 *	"java.lang",
 *	"org.springframework.messaging.support",
 *	"org.springframework.integration.support",
 *	"org.springframework.integration.message",
 *	"org.springframework.integration.store"
 * </pre>
 * <p>
 * To add more packages, create an object mapper using
 * {@link JacksonJsonUtils#messagingAwareMapper(String...)}.
 * <p>
 * A constructor is provided allowing the provision of such a configured object mapper.
 *
 * @author Gary Russell
 *
 * @since 5.0
 *
 */
public class EmbeddedJsonHeadersMessageMapper implements BytesMessageMapper {

	protected final Log logger = LogFactory.getLog(getClass());

	private final ObjectMapper objectMapper;

	private final List<String> headerPatterns;

	private final boolean allHeaders;

	private boolean rawBytes = true;

	private boolean caseSensitive;

	/**
	 * Construct an instance that embeds all headers, using the default
	 * JSON Object mapper.
	 */
	public EmbeddedJsonHeadersMessageMapper() {
		this("*");
	}

	/**
	 * Construct an instance that embeds headers matching the supplied patterns, using
	 * the default JSON object mapper.
	 * @param headerPatterns the patterns.
	 * @see PatternMatchUtils#simpleMatch(String, String)
	 */
	public EmbeddedJsonHeadersMessageMapper(String... headerPatterns) {
		this(JacksonJsonUtils.messagingAwareMapper(), headerPatterns);
	}

	/**
	 * Construct an instance that embeds all headers, using the
	 * supplied JSON object mapper.
	 * @param objectMapper the object mapper.
	 */
	public EmbeddedJsonHeadersMessageMapper(ObjectMapper objectMapper) {
		this(objectMapper, "*");
	}

	/**
	 * Construct an instance that embeds headers matching the supplied patterns using the
	 * supplied JSON object mapper.
	 * @param objectMapper the object mapper.
	 * @param headerPatterns the patterns.
	 */
	public EmbeddedJsonHeadersMessageMapper(ObjectMapper objectMapper, String... headerPatterns) {
		this.objectMapper = objectMapper;
		this.headerPatterns = Arrays.asList(headerPatterns);
		this.allHeaders = this.headerPatterns.size() == 1 && this.headerPatterns.get(0).equals("*");
	}

	/**
	 * For messages with {@code byte[]} payloads, if rendered as JSON, Jackson performs
	 * Base64 conversion on the bytes. If this property is true (default), the result has
	 * the form &lt;headersLen&gt;&lt;headers&gt;&lt;payloadLen&gt;&lt;payload&gt;; with
	 * the headers rendered in JSON and the payload unchanged. Set to false to render
	 * the bytes as base64.
	 * @param rawBytes false to encode as base64.
	 */
	public void setRawBytes(boolean rawBytes) {
		this.rawBytes = rawBytes;
	}

	/**
	 * Set to true to make the header name pattern match case sensitive.
	 * Default false.
	 * @param caseSensitive true to make case sensitive.
	 */
	public void setCaseSensitive(boolean caseSensitive) {
		this.caseSensitive = caseSensitive;
	}

	public Collection<String> getHeaderPatterns() {
		return Collections.unmodifiableCollection(this.headerPatterns);
	}

	@SuppressWarnings("unchecked")
	@Override
	public byte[] fromMessage(Message<?> message) throws Exception {
		Message<?> messageToEncode = this.allHeaders ? message : pruneHeaders(message);
		if (this.rawBytes && message.getPayload() instanceof byte[]) {
			return fromBytesPayload((Message<byte[]>) messageToEncode);
		}
		else {
			return this.objectMapper.writeValueAsBytes(messageToEncode);
		}
	}

	private Message<?> pruneHeaders(Message<?> message) {
		Map<String, Object> headersToEmbed =
		message.getHeaders().entrySet().stream()
			.filter(e -> this.headerPatterns.stream().anyMatch(p ->
				this.caseSensitive
					? PatternMatchUtils.simpleMatch(p, e.getKey())
					: PatternMatchUtils.simpleMatch(p.toLowerCase(), e.getKey().toLowerCase())))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		return new MutableMessage<>(message.getPayload(), headersToEmbed);
	}

	private byte[] fromBytesPayload(Message<byte[]> message) throws Exception {
		byte[] headers = this.objectMapper.writeValueAsBytes(message.getHeaders());
		byte[] payload = message.getPayload();
		ByteBuffer buffer = ByteBuffer.wrap(new byte[8 + headers.length + payload.length]);
		buffer.putInt(headers.length);
		buffer.put(headers);
		buffer.putInt(payload.length);
		buffer.put(payload);
		return buffer.array();
	}

	@Override
	public Message<?> toMessage(byte[] bytes) throws Exception {
		Message<?> message = null;
		try {
			message = decodeNativeFormat(bytes);
		}
		catch (Exception e) {
			// empty
		}
		if (message == null) {
			try {
				message = (Message<?>) this.objectMapper.readValue(bytes, Object.class);
			}
			catch (Exception e) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Failed to decode JSON", e);
				}
			}
		}
		if (message != null) {
			return message;
		}
		else {
			return new GenericMessage<>(bytes);
		}
	}

	private Message<?> decodeNativeFormat(byte[] bytes) throws Exception {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		if (buffer.remaining() > 4) {
			int headersLen = buffer.getInt();
			if (headersLen >= 0 && headersLen < buffer.remaining() - 4) {
				buffer.position(headersLen + 4);
				int payloadLen = buffer.getInt();
				if (payloadLen != buffer.remaining()) {
					return null;
				}
				else {
					buffer.position(4);
					@SuppressWarnings("unchecked")
					Map<String, Object> headers = this.objectMapper.readValue(bytes, buffer.position(), headersLen,
							HashMap.class);
					buffer.position(buffer.position() + headersLen);
					buffer.getInt();
					Object payload;
					byte[] payloadBytes = new byte[payloadLen];
					buffer.get(payloadBytes);
					payload = payloadBytes;
					return new GenericMessage<Object>(payload, new MutableMessageHeaders(headers));
				}
			}
		}
		return null;
	}

}
