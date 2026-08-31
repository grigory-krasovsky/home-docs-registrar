package com.example.homedocsregistrar.access;

import com.example.homedocsregistrar.domain.AllowedUser;
import com.example.homedocsregistrar.repository.AllowedUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runtime access control backed by the {@code allowed_user} table (no restart to add/remove people).
 * The first admin bootstraps via {@link #claim}; admins then approve access requests via {@link #approve}.
 */
@Service
public class AccessService {

    private final AllowedUserRepository users;

    public AccessService(AllowedUserRepository users) {
        this.users = users;
    }

    /** Whether the user may use the bot at all. */
    @Transactional(readOnly = true)
    public boolean isAllowed(Long userId) {
        return userId != null && users.existsById(userId);
    }

    /** Whether the user may approve access requests. */
    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return userId != null && users.findById(userId).map(AllowedUser::isAdmin).orElse(false);
    }

    /** Admins to notify about access requests. */
    @Transactional(readOnly = true)
    public List<AllowedUser> admins() {
        return users.findByAdminTrue();
    }

    /** Grant a regular user access (idempotent); returns false if they already had access. */
    @Transactional
    public boolean approve(Long userId, String displayName) {
        if (userId == null || users.existsById(userId)) {
            return false;
        }
        users.save(new AllowedUser(userId, false, displayName));
        return true;
    }
}
