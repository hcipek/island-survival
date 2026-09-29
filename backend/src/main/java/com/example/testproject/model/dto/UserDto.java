package com.example.testproject.model.dto;

import com.example.testproject.model.User;

public record UserDto(long id, String name, String color) {

    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getName(), user.getColor());
    }
}
