package com.example.testproject.repository;

import com.example.testproject.model.InventorySlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventorySlotRepository extends JpaRepository<InventorySlot, Long> {

    List<InventorySlot> findAllByWorldIdAndUserIdOrderBySlotIndex(Long worldId, Long userId);

    List<InventorySlot> findAllByWorldId(Long worldId);
}
