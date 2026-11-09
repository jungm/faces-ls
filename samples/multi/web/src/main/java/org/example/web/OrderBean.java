package org.example.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

import java.math.BigDecimal;

@Named
@RequestScoped
public class OrderBean {
    private BigDecimal total = new BigDecimal("42.50");
    private String customer;

    public BigDecimal getTotal() {
        return total;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }
}
