package com.vladoose.nir.mapper;

import com.vladoose.nir.dto.request.UserRequest;
import com.vladoose.nir.dto.response.UserResponse;
import com.vladoose.nir.entity.UserAccount;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(UserAccount entity);

    List<UserResponse> toResponseList(List<UserAccount> entities);

    /**
     * Builds an entity from a request WITHOUT touching the password hash.
     * The controller is responsible for hashing {@code request.password}
     * and assigning {@code entity.passwordHash}.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    UserAccount toEntity(UserRequest request);

    /**
     * Partial update — never writes {@code passwordHash} (handled in controller)
     * and never overrides non-null fields with nulls.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    void updateEntity(UserRequest request, @MappingTarget UserAccount entity);
}
