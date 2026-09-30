package com.example.testproject.repository;

import com.example.testproject.model.Building;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    /// [type] is a building type's name - see [Building#getType].
    long countByWorldIdAndOwnerIdAndType(Long worldId, Long ownerId, String type);

    List<Building> findAllByWorldId(Long worldId);
}
