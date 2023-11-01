package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.dto.InventoryResponse;
import com.ecommerce.orderservice.dto.OrderLineItemDto;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.event.OrderPlacedEvent;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderLineItems;
import com.ecommerce.orderservice.repository.OrderRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    public String placeOrder(OrderRequest orderRequest){
        Order order=new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        List<OrderLineItems> orderLineItems=orderRequest.getOrderLineItemDto().stream()
                .map(this::mapToDto)
                .toList();
        order.setOrderLineItems(orderLineItems);

        List<String> skuCodes=order.getOrderLineItems().stream().map(OrderLineItems::getSkuCode).toList();
        Span inventoryServiceLookUp= tracer.nextSpan().name("InventoryServiceLookUp");
        try(Tracer.SpanInScope spanInScope=tracer.withSpan(inventoryServiceLookUp.start())){
            InventoryResponse[] invResponseArray=webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode",skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();


            boolean allProductInStock=Arrays.stream(invResponseArray != null ? invResponseArray : new InventoryResponse[0]).allMatch(InventoryResponse::isInStock);
            if(allProductInStock){
                orderRepository.save(order);
                kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
                return "Order placed successfully ";
            }else {
                throw new IllegalArgumentException("Product is not in stock, Please, try Later!!");
            }
        }finally {
            inventoryServiceLookUp.end();
        }

    }

    private OrderLineItems mapToDto(OrderLineItemDto orderLineItemDto) {
        System.out.println(orderLineItemDto);
    OrderLineItems orderLineItem= new OrderLineItems();
    orderLineItem.setPrice(orderLineItemDto.getPrice());
    orderLineItem.setQuantity(orderLineItemDto.getQuantity());
    orderLineItem.setSkuCode(orderLineItemDto.getSkuCode());
    return orderLineItem;
    }
}
