package com.example.orderservice;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final RestClient restClient;

    public OrderController(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("http://localhost:8090").build(); // Base URL for API Gateway
    }

    @GetMapping
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @PostMapping
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackForUserService")
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        // 1. Verify user exists by calling User Service
        UserDTO user = restClient.get()
                .uri("/users/{id}", order.getUserId())
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    throw new RuntimeException("User not found");
                })
                .body(UserDTO.class);

        if (user == null) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // Or a more specific error
        }

        // 2. Get product details and verify stock by calling Product Service
        double totalAmount = 0.0;
        for (OrderItem item : order.getOrderItems()) {
            ProductDTO product = restClient.get()
                    .uri("/products/{id}", item.getProductId())
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw new RuntimeException("Product not found");
                    })
                    .body(ProductDTO.class);

            if (product == null || product.getStockQuantity() < item.getQuantity()) {
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST); // Or a more specific error
            }
            item.setPrice(product.getPrice()); // Set the price from the product service
            totalAmount += item.getPrice() * item.getQuantity();
            // TODO: In a real scenario, you'd also update product stock here (e.g., via
            // another REST call or event)
        }

        // 3. Set order details
        order.setOrderDate(java.time.LocalDateTime.now());
        order.setStatus("PENDING");
        order.setTotalAmount(new java.math.BigDecimal(totalAmount));

        // 4. Save the order
        Order savedOrder = orderRepository.save(order);

        // Publish OrderCreatedEvent to RabbitMQ
        rabbitTemplate.convertAndSend("orderQueue", "Order created: " + savedOrder.getId());

        return new ResponseEntity<>(savedOrder, HttpStatus.CREATED);
    }

    private ResponseEntity<Order> fallbackForUserService(Order order, Throwable t) {
        System.err.println("Fallback for userService: " + t.getMessage());
        return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @GetMapping("/{id}")
    public Order getOrderById(@PathVariable Long id) {
        return orderRepository.findById(id).orElse(null);
    }
}
