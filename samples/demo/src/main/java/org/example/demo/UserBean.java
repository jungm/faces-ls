package org.example.demo;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing bean for the user pages.
 */
@Named
@SessionScoped
public class UserBean implements Serializable {
    private final List<User> users = new ArrayList<>();
    private User current = new User();
    private String filter;

    /** Users matching the current filter. */
    public List<User> getUsers() {
        return users;
    }

    public User getCurrent() {
        return current;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    /** Saves the current user and navigates to the list. */
    public String save() {
        users.add(current);
        current = new User();
        return "users";
    }

    public void delete(User user) {
        users.remove(user);
    }

    public int count() {
        return users.size();
    }
}
