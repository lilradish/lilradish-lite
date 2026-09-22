package org.lilradish.lite.identification;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.stereotype.Component;

/**
 * Refuses to start where nothing implements {@link UserIdentification}, and says which thing is
 * missing.
 *
 * <p>The archive deliberately carries none. What a credential looks like and how it travelled is
 * the deployment's, so an application deployed as it is built has no way to learn who is calling,
 * and the deployment has to supply one.
 *
 * <p>Read off the bean definitions rather than left to a constructor parameter: unsupplied, the
 * absence surfaces as whichever bean happened to ask for one first, which names a consequence and
 * leaves a deployer to work the cause back out of it.
 */
@Component
final class UserIdentificationRequired implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Definitions rather than instances, and no eager initialisation: this runs before any
        // singleton is created, which is the whole point of refusing here.
        if (beanFactory.getBeanNamesForType(UserIdentification.class, true, false).length == 0) {
            throw new ApplicationContextException("This deployment carries no UserIdentification, so it has no way "
                    + "to learn which user is calling. Supply exactly one implementation of "
                    + UserIdentification.class.getName() + " as a bean.");
        }
    }
}
