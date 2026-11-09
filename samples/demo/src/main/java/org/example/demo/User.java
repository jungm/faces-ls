package org.example.demo;

import java.time.LocalDate;
import java.util.List;

/**
 * A registered user.
 */
public class User {
    private String name;
    private String email;
    private LocalDate birthday;
    private boolean admin;
    private List<String> roles = List.of();

    /** The display name. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public List<String> getRoles() {
        return roles;
    }
}
