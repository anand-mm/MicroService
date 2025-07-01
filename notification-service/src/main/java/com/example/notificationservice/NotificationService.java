package com.example.notificationservice;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    @RabbitListener(queues = "orderQueue")
    public void handleOrderNotification(String message) {
        System.out.println("Received order notification: " + message);
        // In a real application, you would send an email, push notification, etc.
    }
}
