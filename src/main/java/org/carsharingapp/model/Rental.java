package org.carsharingapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "rentals")
public class Rental {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull
    @Column(nullable = false)
    private LocalDate rentalDate;
    @NotNull
    @Column(nullable = false)
    private LocalDate returnDate;
    private LocalDate actualReturnDate;
    @NotNull
    @Column(nullable = false)
    private Long carId;
    @NotNull
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private boolean active = true;
}
