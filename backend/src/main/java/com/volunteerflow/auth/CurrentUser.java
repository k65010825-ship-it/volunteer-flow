package com.volunteerflow.auth;

public record CurrentUser(
        Long id,
        String username,
        String realName,
        String studentNumber,
        String contact,
        String platformRole
) {
    public static CurrentUser from(AppUser user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getRealName(),
                user.getStudentNumber(), user.getContact(), user.getPlatformRole());
    }
}
