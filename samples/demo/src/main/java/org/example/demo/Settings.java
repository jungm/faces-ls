package org.example.demo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

@Named("appSettings")
@ApplicationScoped
public class Settings {
    public static final int PAGE_SIZE = 20;

    public String getTitle() {
        return "Faces Demo";
    }
}
