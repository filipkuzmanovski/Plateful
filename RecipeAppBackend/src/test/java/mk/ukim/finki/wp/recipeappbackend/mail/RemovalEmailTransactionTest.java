package mk.ukim.finki.wp.recipeappbackend.mail;

import mk.ukim.finki.wp.recipeappbackend.service.CommentRemovedEvent;
import mk.ukim.finki.wp.recipeappbackend.service.RecipeRemovedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Spec section 6: the removal emails go out only after the removal transaction commits.
 * The real RemovalEmailListener (with its @Async + AFTER_COMMIT wiring) runs in a minimal Spring
 * context over a no-op transaction manager, so the real transaction synchronization decides when
 * the listener fires. The executor is synchronous, so a missing call is a real "never sent".
 */
class RemovalEmailTransactionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfig.class)
            .withPropertyValues("plateful.frontend-url=http://localhost:5173");

    @Test
    void noRemovalEmailWhenTheTransactionRollsBack() {
        runner.run(ctx -> {
            ReviewEmailSender sender = ctx.getBean(ReviewEmailSender.class);

            ctx.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                ctx.publishEvent(new RecipeRemovedEvent(UUID.randomUUID(), "Soup", "Spam",
                        "author@example.com", UUID.randomUUID(), UUID.randomUUID()));
                ctx.publishEvent(new CommentRemovedEvent(UUID.randomUUID(), "Soup", "Salty", "Rude",
                        "author@example.com", UUID.randomUUID(), UUID.randomUUID()));
                status.setRollbackOnly();
            });

            verify(sender, never()).send(anyString(), anyString(), anyString());
        });
    }

    @Test
    void removalEmailsAreSentOnlyAfterCommit() {
        runner.run(ctx -> {
            ReviewEmailSender sender = ctx.getBean(ReviewEmailSender.class);

            ctx.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                ctx.publishEvent(new RecipeRemovedEvent(UUID.randomUUID(), "Soup", "Spam",
                        "author@example.com", UUID.randomUUID(), UUID.randomUUID()));
                ctx.publishEvent(new CommentRemovedEvent(UUID.randomUUID(), "Soup", "Salty", "Rude",
                        "author@example.com", UUID.randomUUID(), UUID.randomUUID()));
                verify(sender, never()).send(anyString(), anyString(), anyString());
            });

            verify(sender, times(2)).send(anyString(), anyString(), anyString());
        });
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAsync
    @Import(RemovalEmailListener.class)
    static class WiringConfig implements AsyncConfigurer {

        @Override
        public Executor getAsyncExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        ReviewEmailSender reviewEmailSender() {
            return mock(ReviewEmailSender.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }

    /** Transaction manager without a resource: only the synchronization callbacks are real. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
