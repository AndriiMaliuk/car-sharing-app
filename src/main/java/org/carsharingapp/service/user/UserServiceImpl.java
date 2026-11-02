package org.carsharingapp.service.user;

import lombok.RequiredArgsConstructor;
import org.carsharingapp.dto.user.UpdateUserRequestDto;
import org.carsharingapp.dto.user.UpdateUserRoleRequestDto;
import org.carsharingapp.dto.user.UserRegistrationRequestDto;
import org.carsharingapp.dto.user.UserResponseDto;
import org.carsharingapp.exeption.EntityNotFoundException;
import org.carsharingapp.exeption.RegistrationException;
import org.carsharingapp.mapper.UserMapper;
import org.carsharingapp.model.User;
import org.carsharingapp.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponseDto register(UserRegistrationRequestDto requestDto) {
        if (userRepository.findByEmail(requestDto.getEmail()).isPresent()) {
            throw new RegistrationException("Can't register user with email: "
                    + requestDto.getEmail()
                    + "; email is already registered");
        }
        User user = userMapper.toModel(requestDto);
        user.setPassword(passwordEncoder.encode(requestDto.getPassword()));
        user.setRole(User.Role.CUSTOMER);
        userRepository.save(user);
        return userMapper.toDto(user);
    }

    @Override
    @Transactional
    public void update(UpdateUserRequestDto requestDto) {
        User user = getAuthenticatedUser();
        userMapper.updateUserFromDto(requestDto, user);
        userRepository.save(user);
    }

    @Override
    public UserResponseDto findUser() {
        return userMapper.toDto(getAuthenticatedUser());
    }

    @Override
    @Transactional
    public void updateUserRole(Long id, UpdateUserRoleRequestDto requestDto) {
        User user = userRepository.findById(id).orElseThrow(() ->
                new EntityNotFoundException("Can't find user with id: " + id));
        user.setRole(requestDto.getRoleName());
        userRepository.save(user);
    }

    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        throw new RuntimeException("Can't find authenticated user; authentication present: "
                + (authentication != null));
    }
}
