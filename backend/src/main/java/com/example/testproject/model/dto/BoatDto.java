package com.example.testproject.model.dto;

import com.example.testproject.model.Boat;

public record BoatDto(long id, long ownerId, double x, double y) {

    public static BoatDto from(Boat boat) {
        return new BoatDto(boat.getId(), boat.getOwnerId(), boat.getX(), boat.getY());
    }
}
