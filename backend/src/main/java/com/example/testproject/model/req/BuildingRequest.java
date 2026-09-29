package com.example.testproject.model.req;

import com.example.testproject.model.BuildingType;

public record BuildingRequest(BuildingType type, double x, double y, long ownerId) {
}
