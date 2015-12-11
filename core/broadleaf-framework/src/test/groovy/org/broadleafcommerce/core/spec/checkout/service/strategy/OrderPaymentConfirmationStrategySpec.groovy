/*
 * #%L
 * BroadleafCommerce Framework
 * %%
 * Copyright (C) 2009 - 2015 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.core.spec.checkout.service.strategy

import org.broadleafcommerce.common.config.service.SystemPropertiesService
import org.broadleafcommerce.common.encryption.EncryptionModule
import org.broadleafcommerce.common.money.Money
import org.broadleafcommerce.common.payment.PaymentGatewayType
import org.broadleafcommerce.common.payment.PaymentTransactionType
import org.broadleafcommerce.common.payment.PaymentType
import org.broadleafcommerce.common.payment.dto.PaymentRequestDTO
import org.broadleafcommerce.common.payment.dto.PaymentResponseDTO
import org.broadleafcommerce.common.payment.service.*
import org.broadleafcommerce.core.checkout.service.exception.CheckoutException
import org.broadleafcommerce.core.checkout.service.strategy.OrderPaymentConfirmationStrategy
import org.broadleafcommerce.core.checkout.service.strategy.OrderPaymentConfirmationStrategyImpl
import org.broadleafcommerce.core.checkout.service.workflow.CheckoutSeed
import org.broadleafcommerce.core.order.domain.Order
import org.broadleafcommerce.core.order.domain.OrderImpl
import org.broadleafcommerce.core.payment.domain.OrderPayment
import org.broadleafcommerce.core.payment.domain.OrderPaymentImpl
import org.broadleafcommerce.core.payment.domain.PaymentTransaction
import org.broadleafcommerce.core.payment.domain.PaymentTransactionImpl
import org.broadleafcommerce.core.payment.domain.secure.CreditCardPayment
import org.broadleafcommerce.core.payment.domain.secure.CreditCardPaymentInfoImpl
import org.broadleafcommerce.core.payment.service.OrderToPaymentRequestDTOService
import org.broadleafcommerce.core.payment.service.SecureOrderPaymentService
import org.broadleafcommerce.core.workflow.DefaultProcessContextImpl
import org.broadleafcommerce.core.workflow.ProcessContext
import spock.lang.Specification

/**
 * @author Elbert Bautista (elbertbautista)
 */
class OrderPaymentConfirmationStrategySpec extends Specification {

    ProcessContext<CheckoutSeed> context;
    Order order = new OrderImpl()

    OrderPayment confirmedCC = new OrderPaymentImpl()
    PaymentTransaction confirmedCCTransaction = new PaymentTransactionImpl()

    OrderPayment unconfirmedTP = new OrderPaymentImpl()
    PaymentTransaction unconfirmedTPTransaction = new PaymentTransactionImpl()

    OrderPayment unconfirmedCC = new OrderPaymentImpl()
    PaymentTransaction unconfirmedCCTransaction = new PaymentTransactionImpl()

    CreditCardPayment secureReference = new CreditCardPaymentInfoImpl()

    def setup() {
        confirmedCC.amount = new Money(10)
        confirmedCC.type = PaymentType.CREDIT_CARD
        confirmedCC.order = order

        confirmedCCTransaction.type = PaymentTransactionType.AUTHORIZE_AND_CAPTURE
        confirmedCCTransaction.amount = new Money(10)
        confirmedCCTransaction.success = true
        confirmedCCTransaction.orderPayment = confirmedCC

        confirmedCC.transactions << confirmedCCTransaction

        unconfirmedTP.amount = new Money(12)
        unconfirmedTP.type = PaymentType.THIRD_PARTY_ACCOUNT
        unconfirmedTP.order = order

        unconfirmedTPTransaction.type = PaymentTransactionType.UNCONFIRMED
        unconfirmedTPTransaction.amount = new Money(12)
        unconfirmedTPTransaction.success = true
        unconfirmedTPTransaction.orderPayment = unconfirmedTP

        unconfirmedTP.transactions << unconfirmedTPTransaction

        unconfirmedCC.amount = new Money(15)
        unconfirmedCC.type = PaymentType.CREDIT_CARD
        unconfirmedCC.order = order
        unconfirmedCC.referenceNumber = 12345

        unconfirmedCCTransaction.type = PaymentTransactionType.UNCONFIRMED
        unconfirmedCCTransaction.amount = new Money(15)
        unconfirmedCCTransaction.success = true
        unconfirmedCCTransaction.orderPayment = unconfirmedCC

        unconfirmedCC.transactions << unconfirmedCCTransaction

        EncryptionModule mockEncryptionModule = Mock()
        mockEncryptionModule.encrypt(_ as String) > "xxxxxxxxxxx"
        secureReference.with {
            encryptionModule = mockEncryptionModule
        }
        secureReference.referenceNumber = 12345
        secureReference.nameOnCard = "Bill Broadleaf"
        secureReference.pan = "4111111111111"
        secureReference.expirationYear = 2050
        secureReference.expirationMonth = 1

        context = new DefaultProcessContextImpl<CheckoutSeed>().with{
            seedData = new CheckoutSeed(order, null)
            it
        }
    }

    def "Test undefined PaymentGatewayConfigurationServiceProvider"() {
        setup: "I have a strategy but no provider implemented"
        OrderPaymentConfirmationStrategy strategy = new OrderPaymentConfirmationStrategyImpl().with {
            it
        }

        when: "I execute the strategy with an unconfirmed credit card transaction"
        PaymentResponseDTO response = strategy.confirmTransaction(unconfirmedCCTransaction, context);

        then: "A CheckoutException should be thrown stating that there is no provider configured"
        CheckoutException ex = thrown()
        ex.message == "There are unconfirmed payment transactions on this payment but no payment gateway" +
                " configuration or transaction confirmation service configured"

    }

    def "Test confirming an UNCONFIRMED CREDIT_CARD"() {
        setup: "I have initialized the strategy"
        //Initiate Mocks
        PaymentResponseDTO responseDTO = new PaymentResponseDTO(PaymentType.CREDIT_CARD, PaymentGatewayType.PASSTHROUGH)
                .amount(new Money(15))
                .rawResponse("TEST")
                .successful(true)
                .paymentTransactionType(PaymentTransactionType.AUTHORIZE_AND_CAPTURE)

        SystemPropertiesService mockSPS = Mock()
        mockSPS.resolveBooleanSystemProperty(*_) >> false

        PaymentGatewayConfiguration mockConfiguration = Mock()
        mockConfiguration.isPerformAuthorizeAndCapture() >> true

        PaymentGatewayTransactionService mockTransactionService = Mock()
        mockTransactionService.authorizeAndCapture(_) >> responseDTO

        PaymentGatewayConfigurationService mockConfigService = Mock()
        mockConfigService.getTransactionService() >> mockTransactionService
        mockConfigService.getConfiguration() >> mockConfiguration

        PaymentGatewayConfigurationServiceProvider mockProvider = Mock()
        mockProvider.getGatewayConfigurationService(_) >> {PaymentGatewayType type -> mockConfigService}
        OrderToPaymentRequestDTOService mockRequestService = Mock()
        mockRequestService.translatePaymentTransaction(*_) >> new PaymentRequestDTO()

        SecureOrderPaymentService mockSecurePaymentService = Mock()
        mockSecurePaymentService.findSecurePaymentInfo(*_) >> secureReference

        OrderPaymentConfirmationStrategy strategy = new OrderPaymentConfirmationStrategyImpl().with {
            systemPropertiesService = mockSPS
            secureOrderPaymentService = mockSecurePaymentService
            orderToPaymentRequestService = mockRequestService
            paymentConfigurationServiceProvider = mockProvider
            it
        }

        when: "I execute the strategy with an unconfirmed credit card transaction"
        PaymentResponseDTO response = strategy.confirmTransaction(unconfirmedCCTransaction, context);

        then: "The response should be successful"
        response.successful
        response.paymentType == PaymentType.CREDIT_CARD
        response.paymentTransactionType == PaymentTransactionType.AUTHORIZE_AND_CAPTURE
        response.amount == new Money(15)
    }

    def "Test confirming an UNCONFIRMED THIRD_PARTY_ACCOUNT"() {
        setup: "I have initialized the strategy"
        //Initiate Mocks
        PaymentResponseDTO responseDTO = new PaymentResponseDTO(PaymentType.THIRD_PARTY_ACCOUNT, PaymentGatewayType.PASSTHROUGH)
                .amount(new Money(15))
                .rawResponse("TEST")
                .successful(true)
                .paymentTransactionType(PaymentTransactionType.AUTHORIZE_AND_CAPTURE)

        SystemPropertiesService mockSPS = Mock()
        mockSPS.resolveBooleanSystemProperty(*_) >> false

        PaymentGatewayConfiguration mockConfiguration = Mock()
        mockConfiguration.isPerformAuthorizeAndCapture() >> true

        PaymentGatewayTransactionConfirmationService mockConfirmationService = Mock()
        mockConfirmationService.confirmTransaction(_) >> responseDTO

        PaymentGatewayConfigurationService mockConfigService = Mock()
        mockConfigService.getTransactionConfirmationService() >> mockConfirmationService
        mockConfigService.getConfiguration() >> mockConfiguration

        PaymentGatewayConfigurationServiceProvider mockProvider = Mock()
        mockProvider.getGatewayConfigurationService(_) >> {PaymentGatewayType type -> mockConfigService}
        OrderToPaymentRequestDTOService mockRequestService = Mock()
        mockRequestService.translatePaymentTransaction(*_) >> new PaymentRequestDTO()

        SecureOrderPaymentService mockSecurePaymentService = Mock()
        mockSecurePaymentService.findSecurePaymentInfo(*_) >> secureReference

        OrderPaymentConfirmationStrategy strategy = new OrderPaymentConfirmationStrategyImpl().with {
            systemPropertiesService = mockSPS
            secureOrderPaymentService = mockSecurePaymentService
            orderToPaymentRequestService = mockRequestService
            paymentConfigurationServiceProvider = mockProvider
            it
        }

        when: "I execute the strategy with an unconfirmed third party transaction"
        PaymentResponseDTO response = strategy.confirmTransaction(unconfirmedTPTransaction, context);

        then: "The response should be successful "
        response.successful
        response.paymentType == PaymentType.THIRD_PARTY_ACCOUNT
        response.paymentTransactionType == PaymentTransactionType.AUTHORIZE_AND_CAPTURE
        response.amount == new Money(15)
    }

}