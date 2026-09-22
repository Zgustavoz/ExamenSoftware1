package com.diagramas.platform.access.service;

import com.diagramas.platform.access.domain.Company;
import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.repository.CompanyRepository;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.config.AppProperties;
import com.diagramas.platform.common.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Datos semilla (5.4): empresa {@code platform} + SOFTWARE_ADMIN con la contraseña de SEED_ADMIN_PASSWORD.
 * En perfil {@code dev} además crea la empresa demo con un usuario de cada rol.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    public static final String ADMIN_USERNAME = "admin";

    private final CompanyRepository companies;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Environment env;

    public DataInitializer(
            CompanyRepository companies, UserRepository users, PasswordEncoder encoder, AppProperties props, Environment env) {
        this.companies = companies;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.env = env;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = props.seedAdminPassword();
        Company platform = companies.findBySlug(CompanyService.PLATFORM_SLUG).orElse(null);
        boolean adminMissing = platform == null
                || users.findByCompanyIdAndUsername(platform.getId(), ADMIN_USERNAME).isEmpty();
        if (adminMissing && (password == null || password.isBlank())) {
            throw new IllegalStateException("SEED_ADMIN_PASSWORD es obligatorio para crear el SOFTWARE_ADMIN inicial.");
        }
        if (platform == null) {
            platform = newCompany("Platform", CompanyService.PLATFORM_SLUG);
            log.info("Empresa semilla 'platform' creada");
        }
        if (adminMissing) {
            newUser(platform, ADMIN_USERNAME, "Software Admin", password, AuthPrincipal.SOFTWARE_ADMIN);
            log.info("Usuario SOFTWARE_ADMIN '{}' creado", ADMIN_USERNAME);
        }
        if (env.acceptsProfiles(Profiles.of("dev")) && password != null && !password.isBlank()
                && companies.findBySlug("demo").isEmpty()) {
            Company demo = newCompany("Empresa Demo", "demo");
            newUser(demo, "companyadmin", "Admin Demo", password, AuthPrincipal.COMPANY_ADMIN);
            newUser(demo, "designer", "Diseñador Demo", password, AuthPrincipal.DESIGNER);
            newUser(demo, "developer", "Desarrollador Demo", password, AuthPrincipal.DEVELOPER);
            log.info("Empresa demo (slug 'demo') creada con un usuario por rol");
        }
    }

    private Company newCompany(String name, String slug) {
        Company c = new Company();
        c.setName(name);
        c.setSlug(slug);
        return companies.save(c);
    }

    private void newUser(Company company, String username, String fullName, String password, String role) {
        User u = new User();
        u.setCompanyId(company.getId());
        u.setUsername(username);
        u.setEmail(username + "@" + company.getSlug() + ".local");
        u.setFullName(fullName);
        u.setPasswordHash(encoder.encode(password));
        u.setRoles(new ArrayList<>(List.of(role)));
        users.save(u);
    }
}
