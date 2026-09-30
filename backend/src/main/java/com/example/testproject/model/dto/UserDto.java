package com.example.testproject.model.dto;

import com.example.testproject.model.User;

/// A user, and whether someone is playing as them right now - they can't be
/// picked by anyone else until they leave.
public record UserDto(long id, String name, String color, boolean playing) {

    public static UserDto from(User user, boolean playing) {
        return new UserDto(user.getId(), user.getName(), user.getColor(), playing);
    }
}
