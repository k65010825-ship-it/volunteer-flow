package com.volunteerflow.organization;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import com.volunteerflow.rbac.RbacPermission;
import com.volunteerflow.rbac.RbacRole;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for organizations, invitations, memberships, and organization-scoped roles. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class OrganizationController {
  private final OrganizationService service;

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  @PostMapping("/organizations")
  public ResponseEntity<ApiResponse<Organization>> create(
      @AuthenticationPrincipal CurrentUser currentUser,
      @Valid @RequestBody OrganizationService.CreateOrganizationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.of(service.create(currentUser.id(), currentUser.platformRole(), request)));
  }

  @GetMapping("/organizations")
  public ApiResponse<List<Organization>> list(@AuthenticationPrincipal CurrentUser currentUser) {
    return ApiResponse.of(service.list(currentUser.id(), currentUser.platformRole()));
  }

  @GetMapping("/organizations/{id}")
  public ApiResponse<Organization> get(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.get(currentUser.id(), currentUser.platformRole(), id));
  }

  @PostMapping("/organizations/{id}/deactivation")
  public ResponseEntity<Void> deactivate(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    service.deactivateOrganization(currentUser.id(), currentUser.platformRole(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/organizations/{id}/invitations")
  public ResponseEntity<ApiResponse<InviteCode>> invite(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @Valid @RequestBody CreateInviteRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.createInvite(currentUser.id(), id, request)));
  }

  @PostMapping("/organization-memberships")
  public ResponseEntity<ApiResponse<OrganizationMember>> join(
      @AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody JoinRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.joinByInvite(currentUser.id(), request.code())));
  }

  @PostMapping("/organizations/{id}/invitations/{inviteId}/deactivation")
  public ResponseEntity<Void> deactivateInvite(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long inviteId) {
    service.deactivateInvite(currentUser.id(), id, inviteId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/organizations/{id}/members")
  public ApiResponse<List<OrganizationMember>> members(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.members(currentUser.id(), id));
  }

  @PatchMapping("/organizations/{id}/members/{memberId}/role")
  public ApiResponse<OrganizationMember> assign(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long memberId,
      @Valid @RequestBody OrganizationService.AssignRoleRequest request) {
    return ApiResponse.of(service.assignRole(currentUser.id(), id, memberId, request));
  }

  @GetMapping("/organizations/{id}/roles")
  public ApiResponse<List<RbacRole>> roles(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.roles(currentUser.id(), id));
  }

  @GetMapping("/organizations/{id}/permissions")
  public ApiResponse<List<RbacPermission>> permissions(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.permissions(currentUser.id(), id));
  }

  @PostMapping("/organizations/{id}/roles")
  public ResponseEntity<ApiResponse<RbacRole>> role(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @Valid @RequestBody OrganizationService.CreateRoleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.createRole(currentUser.id(), id, request)));
  }

  @PutMapping("/organizations/{id}/roles/{roleId}/permissions")
  public ResponseEntity<Void> permissions(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long roleId,
      @Valid @RequestBody OrganizationService.SetRolePermissionsRequest request) {
    service.setRolePermissions(currentUser.id(), id, roleId, request);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/organizations/{id}/roles/{roleId}")
  public ApiResponse<RbacRole> updateRole(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long roleId,
      @Valid @RequestBody OrganizationService.UpdateRoleRequest request) {
    return ApiResponse.of(service.updateRole(currentUser.id(), id, roleId, request));
  }

  @PostMapping("/organizations/{id}/roles/{roleId}/deactivation")
  public ResponseEntity<Void> deactivateRole(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long roleId) {
    service.deactivateRole(currentUser.id(), id, roleId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/organizations/{id}/roles/{roleId}")
  public ResponseEntity<Void> deleteRole(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @PathVariable Long roleId) {
    service.deleteRole(currentUser.id(), id, roleId);
    return ResponseEntity.noContent().build();
  }

  public record JoinRequest(@jakarta.validation.constraints.NotBlank String code) {}
}
