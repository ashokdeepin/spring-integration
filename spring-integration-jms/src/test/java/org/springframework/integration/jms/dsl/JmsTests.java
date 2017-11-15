/*
 * Copyright 2016-2017 the original author or authors.
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

package org.springframework.integration.jms.dsl;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.ConnectionFactory;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.channel.ChannelInterceptorAware;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.endpoint.MethodInvokingMessageSource;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptorAdapter;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * @author Artem Bilan
 * @author Gary Russell
 * @author Nasko Vasilev
 *
 * @since 5.0
 */
@RunWith(SpringRunner.class)
@DirtiesContext
public class JmsTests {

	@Autowired
	private ListableBeanFactory beanFactory;

	@Autowired
	private ControlBusGateway controlBus;

	@Autowired
	@Qualifier("flow1QueueChannel")
	private PollableChannel outputChannel;

	@Autowired
	@Qualifier("jmsOutboundFlow.input")
	private MessageChannel jmsOutboundInboundChannel;

	@Autowired
	@Qualifier("jmsOutboundInboundReplyChannel")
	private PollableChannel jmsOutboundInboundReplyChannel;

	@Autowired
	@Qualifier("jmsOutboundGatewayFlow.input")
	private MessageChannel jmsOutboundGatewayChannel;

	@Autowired
	private TestChannelInterceptor testChannelInterceptor;

	@Autowired
	private ConnectionFactory jmsConnectionFactory;

	@Autowired
	private PollableChannel jmsPubSubBridgeChannel;

	@Autowired
	@Qualifier("jmsOutboundGateway.handler")
	private MessageHandler jmsOutboundGatewayHandler;

	@Autowired
	private AtomicBoolean jmsMessageDrivenChannelCalled;

	@Autowired
	private AtomicBoolean jmsInboundGatewayChannelCalled;

	@Autowired(required = false)
	@Qualifier("jmsOutboundFlowTemplate")
	private JmsTemplate jmsOutboundFlowTemplate;

	@Autowired(required = false)
	@Qualifier("jmsMessageDrivenRedeliveryFlowContainer")
	private MessageListenerContainer jmsMessageDrivenRedeliveryFlowContainer;

	@Autowired
	private CountDownLatch redeliveryLatch;

	@Test
	public void testPollingFlow() {
		this.controlBus.send("@'jmsTests.ContextConfiguration.integerMessageSource.inboundChannelAdapter'.start()");
		assertThat(this.beanFactory.getBean("integerChannel"), instanceOf(FixedSubscriberChannel.class));
		for (int i = 0; i < 5; i++) {
			Message<?> message = this.outputChannel.receive(20000);
			assertNotNull(message);
			assertEquals("" + i, message.getPayload());
		}
		this.controlBus.send("@'jmsTests.ContextConfiguration.integerMessageSource.inboundChannelAdapter'.stop()");

		assertTrue(((ChannelInterceptorAware) this.outputChannel).getChannelInterceptors()
				.contains(this.testChannelInterceptor));
		assertThat(this.testChannelInterceptor.invoked.get(), Matchers.greaterThanOrEqualTo(5));

	}

	@Test
	public void testJmsOutboundInboundFlow() {
		this.jmsOutboundInboundChannel.send(MessageBuilder.withPayload("hello THROUGH the JMS")
				.setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, "jmsInbound")
				.build());

		Message<?> receive = this.jmsOutboundInboundReplyChannel.receive(10000);

		assertNotNull(receive);
		assertEquals("HELLO THROUGH THE JMS", receive.getPayload());

		this.jmsOutboundInboundChannel.send(MessageBuilder.withPayload("hello THROUGH the JMS")
				.setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, "jmsMessageDriven")
				.build());

		receive = this.jmsOutboundInboundReplyChannel.receive(10000);

		assertNotNull(receive);
		assertEquals("hello through the jms", receive.getPayload());

		assertTrue(this.jmsMessageDrivenChannelCalled.get());

		this.jmsOutboundInboundChannel.send(MessageBuilder.withPayload("    foo    ")
				.setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, "containerSpecDestination")
				.build());

		receive = this.jmsOutboundInboundReplyChannel.receive(10000);

		assertNotNull(receive);
		assertEquals("foo", receive.getPayload());

		assertNotNull(this.jmsOutboundFlowTemplate);
	}

	@Test
	public void testJmsPipelineFlow() {
		assertEquals(new Long(10000),
				TestUtils.getPropertyValue(this.jmsOutboundGatewayHandler, "idleReplyContainerTimeout", Long.class));
		PollableChannel replyChannel = new QueueChannel();
		Message<String> message = MessageBuilder.withPayload("hello through the jms pipeline")
				.setReplyChannel(replyChannel)
				.setHeader("destination", "jmsPipelineTest")
				.build();
		this.jmsOutboundGatewayChannel.send(message);

		Message<?> receive = replyChannel.receive(5000);

		assertNotNull(receive);
		assertEquals("HELLO THROUGH THE JMS PIPELINE", receive.getPayload());

		assertTrue(this.jmsInboundGatewayChannelCalled.get());
	}

	@Test
	public void testPubSubFlow() {
		JmsTemplate template = new JmsTemplate(this.jmsConnectionFactory);
		template.setPubSubDomain(true);
		template.setDefaultDestinationName("pubsub");
		template.convertAndSend("foo");
		Message<?> received = this.jmsPubSubBridgeChannel.receive(5000);
		assertNotNull(received);
		assertEquals("foo", received.getPayload());
	}

	@Test
	public void testJmsRedeliveryFlow() throws InterruptedException {
		this.jmsOutboundInboundChannel.send(MessageBuilder.withPayload("foo")
				.setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, "jmsMessageDrivenRedelivery")
				.build());

		assertTrue(this.redeliveryLatch.await(10, TimeUnit.SECONDS));

		assertNotNull(this.jmsMessageDrivenRedeliveryFlowContainer);
	}

	@MessagingGateway(defaultRequestChannel = "controlBus.input")
	private interface ControlBusGateway {

		void send(String command);

	}

	@Configuration
	@EnableIntegration
	@IntegrationComponentScan
	@ComponentScan
	public static class ContextConfiguration {

		@Bean
		public ConnectionFactory cachingConnectionFactory() {
			return new CachingConnectionFactory(jmsConnectionFactory());
		}

		@Bean
		public ActiveMQConnectionFactory jmsConnectionFactory() {
			ActiveMQConnectionFactory activeMQConnectionFactory = new ActiveMQConnectionFactory(
					"vm://localhost?broker.persistent=false");
			activeMQConnectionFactory.setTrustAllPackages(true);
			return activeMQConnectionFactory;
		}

		@Bean
		public JmsTemplate jmsTemplate() {
			return new JmsTemplate(jmsConnectionFactory());
		}

		@Bean(name = PollerMetadata.DEFAULT_POLLER)
		public PollerMetadata poller() {
			return Pollers.fixedRate(500).get();
		}

		@Bean
		public IntegrationFlow controlBus() {
			return IntegrationFlowDefinition::controlBus;
		}

		@Bean
		@InboundChannelAdapter(value = "flow1.input", autoStartup = "false", poller = @Poller(fixedRate = "100"))
		public MessageSource<?> integerMessageSource() {
			MethodInvokingMessageSource source = new MethodInvokingMessageSource();
			source.setObject(new AtomicInteger());
			source.setMethodName("getAndIncrement");
			return source;
		}

		@Bean
		public IntegrationFlow flow1() {
			return f -> f
					.fixedSubscriberChannel("integerChannel")
					.transform("payload.toString()")
					.channel(Jms.pollableChannel("flow1QueueChannel", cachingConnectionFactory())
							.destination("flow1QueueChannel"));
		}

		@Bean
		public IntegrationFlow jmsOutboundFlow() {
			return f -> f
					.handle(Jms.outboundAdapter(jmsConnectionFactory())
							.destinationExpression("headers." + SimpMessageHeaderAccessor.DESTINATION_HEADER)
							.configureJmsTemplate(t -> t.id("jmsOutboundFlowTemplate")));
		}

		@Bean
		public MessageChannel jmsOutboundInboundReplyChannel() {
			return MessageChannels.queue().get();
		}

		@Bean
		public IntegrationFlow jmsInboundFlow() {
			return IntegrationFlows
					.from(Jms.inboundAdapter(cachingConnectionFactory()).destination("jmsInbound"))
					.<String, String>transform(String::toUpperCase)
					.channel(this.jmsOutboundInboundReplyChannel())
					.get();
		}

		@Bean
		public IntegrationFlow pubSubFlow() {
			return IntegrationFlows
					.from(Jms.publishSubscribeChannel(jmsConnectionFactory())
							.destination("pubsub"))
					.channel(c -> c.queue("jmsPubSubBridgeChannel"))
					.get();
		}

		@Bean
		public IntegrationFlow jmsMessageDrivenFlow() {
			return IntegrationFlows
					.from(Jms.messageDrivenChannelAdapter(jmsConnectionFactory())
							.outputChannel(jmsMessageDrivenInputChannel())
							.destination("jmsMessageDriven"))
					.<String, String>transform(String::toLowerCase)
					.channel(jmsOutboundInboundReplyChannel())
					.get();
		}

		@Bean
		public AtomicBoolean jmsMessageDrivenChannelCalled() {
			return new AtomicBoolean();
		}

		@Bean
		public MessageChannel jmsMessageDrivenInputChannel() {
			DirectChannel directChannel = new DirectChannel();
			directChannel.addInterceptor(new ChannelInterceptorAdapter() {

				@Override
				public Message<?> preSend(Message<?> message, MessageChannel channel) {
					jmsMessageDrivenChannelCalled().set(true);
					return super.preSend(message, channel);
				}

			});
			return directChannel;
		}

		@Bean
		public IntegrationFlow jmsMessageDrivenFlowWithContainer() {
			return IntegrationFlows
					.from(Jms.messageDrivenChannelAdapter(
							Jms.container(jmsConnectionFactory(), "containerSpecDestination")
									.pubSubDomain(false)
									.taskExecutor(Executors.newCachedThreadPool())
									.get()))
					.transform(String::trim)
					.channel(jmsOutboundInboundReplyChannel())
					.get();
		}

		@Bean
		public IntegrationFlow jmsOutboundGatewayFlow() {
			return f -> f.handle(Jms.outboundGateway(jmsConnectionFactory())
							.replyContainer(c -> c.idleReplyContainerTimeout(10))
							.requestDestination("jmsPipelineTest"),
					e -> e.id("jmsOutboundGateway"));
		}

		@Bean
		public IntegrationFlow jmsInboundGatewayFlow() {
			return IntegrationFlows.from(Jms.inboundGateway(jmsConnectionFactory())
					.requestChannel(jmsInboundGatewayInputChannel())
					.destination("jmsPipelineTest"))
					.<String, String>transform(String::toUpperCase)
					.get();
		}

		@Bean
		public AtomicBoolean jmsInboundGatewayChannelCalled() {
			return new AtomicBoolean();
		}

		@Bean
		public MessageChannel jmsInboundGatewayInputChannel() {
			DirectChannel directChannel = new DirectChannel();
			directChannel.addInterceptor(new ChannelInterceptorAdapter() {

				@Override
				public Message<?> preSend(Message<?> message, MessageChannel channel) {
					jmsInboundGatewayChannelCalled().set(true);
					return super.preSend(message, channel);
				}

			});
			return directChannel;
		}

		@Bean
		public IntegrationFlow jmsMessageDrivenRedeliveryFlow() {
			return IntegrationFlows
					.from(Jms.messageDrivenChannelAdapter(jmsConnectionFactory())
							.errorChannel(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME)
							.destination("jmsMessageDrivenRedelivery")
							.configureListenerContainer(c -> c
									.transactionManager(mock(PlatformTransactionManager.class))
									.id("jmsMessageDrivenRedeliveryFlowContainer")))
					.<String, String>transform(p -> {
						throw new RuntimeException("intentional");
					})
					.get();
		}

		@Bean
		public CountDownLatch redeliveryLatch() {
			return new CountDownLatch(3);
		}

		@Bean
		public IntegrationFlow errorHandlingFlow() {
			return IntegrationFlows.from(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME)
					.handle(m -> {
						MessagingException exception = (MessagingException) m.getPayload();
						redeliveryLatch().countDown();
						throw exception;
					})
					.get();
		}

	}

	@Component
	@GlobalChannelInterceptor(patterns = "flow1QueueChannel")
	public static class TestChannelInterceptor extends ChannelInterceptorAdapter {

		private final AtomicInteger invoked = new AtomicInteger();

		@Override
		public Message<?> preSend(Message<?> message, MessageChannel channel) {
			this.invoked.incrementAndGet();
			return message;
		}

	}

}
