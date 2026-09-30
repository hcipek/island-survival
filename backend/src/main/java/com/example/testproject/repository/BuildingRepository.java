package com.example.testproject.repository;

import com.example.testproject.model.Building;
import com.example.testproject.model.BuildingType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    long countByWorldIdAndOwnerIdAndType(Long worldId, Long ownerId, BuildingType type);

    List<Building> findAllByWorldId(Long worldId);
}
