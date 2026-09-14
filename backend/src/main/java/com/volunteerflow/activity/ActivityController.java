package com.volunteerflow.activity;
import com.volunteerflow.auth.CurrentUser; import com.volunteerflow.infrastructure.web.ApiResponse; import jakarta.validation.Valid; import org.springframework.context.annotation.Profile; import org.springframework.http.*; import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @Profile("!test") @RequestMapping("/api/v1") public class ActivityController {
 private final ActivityService service; public ActivityController(ActivityService s){service=s;}
 @PostMapping("/organizations/{orgId}/activities") public ResponseEntity<ApiResponse<Activity>> create(@AuthenticationPrincipal CurrentUser u,@PathVariable Long orgId,@Valid @RequestBody ActivityService.ActivityRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(u.id(),orgId,r)));}
 @PutMapping("/activities/{id}") public ApiResponse<Activity> update(@AuthenticationPrincipal CurrentUser u,@PathVariable Long id,@Valid @RequestBody ActivityService.ActivityRequest r){return ApiResponse.of(service.update(u.id(),id,r));}
 @PostMapping("/activities/{id}/positions") public ResponseEntity<ApiResponse<ActivityPosition>> position(@AuthenticationPrincipal CurrentUser u,@PathVariable Long id,@Valid @RequestBody ActivityService.PositionRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.addPosition(u.id(),id,r)));}
 @PostMapping("/activities/{id}/publication") public ApiResponse<Activity> publish(@AuthenticationPrincipal CurrentUser u,@PathVariable Long id){return ApiResponse.of(service.publish(u.id(),id));}
 @PostMapping("/activities/{id}/cancellation") public ApiResponse<Activity> cancel(@AuthenticationPrincipal CurrentUser u,@PathVariable Long id,@Valid @RequestBody ActivityService.CancelRequest r){return ApiResponse.of(service.cancel(u.id(),id,r.reason()));}
 @GetMapping("/organizations/{orgId}/activities") public ApiResponse<List<ActivityService.ActivitySummary>> list(@AuthenticationPrincipal CurrentUser u,@PathVariable Long orgId){return ApiResponse.of(service.list(u.id(),orgId));}
 @GetMapping("/activities/{id}") public ApiResponse<ActivityService.ActivityDetail> detail(@AuthenticationPrincipal CurrentUser u,@PathVariable Long id){return ApiResponse.of(service.detail(u.id(),id));}
}
