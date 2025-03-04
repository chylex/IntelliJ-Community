// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections

import com.intellij.codeInspection.*
import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.util.JvmInheritanceUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.util.xml.DomUtil
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.dom.Extension
import org.jetbrains.idea.devkit.inspections.quickfix.ConvertToLightServiceFix
import org.jetbrains.idea.devkit.util.locateExtensionsByPsiClass
import org.jetbrains.uast.UClass

internal class LightServiceMigrationCodeInspection : DevKitUastInspectionBase(UClass::class.java) {

  override fun checkClass(aClass: UClass, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor> {
    val psiClass = aClass.javaPsi
    if (psiClass.isEnum ||
        psiClass.hasModifier(JvmModifier.ABSTRACT) ||
        aClass.isInterface ||
        !aClass.isFinal ||
        aClass.isAnonymousOrLocal()) {
      return ProblemDescriptor.EMPTY_ARRAY
    }
    if (isVersion193OrHigher(psiClass) ||
        ApplicationManager.getApplication().isUnitTestMode) {
      if (isLightService(aClass)) return ProblemDescriptor.EMPTY_ARRAY
      val extensionsCandidates = locateExtensionsByPsiClass(psiClass)
      for (candidate in extensionsCandidates) {
        val extension = DomUtil.findDomElement(candidate.pointer.element, Extension::class.java, false) ?: continue
        val (serviceImplementation, level) = getServiceImplementation(extension) ?: continue
        if (level == Service.Level.APP &&
            JvmInheritanceUtil.isInheritor(aClass, PersistentStateComponent::class.java.canonicalName)) {
          continue
        }
        if (serviceImplementation == psiClass && !containsUnitTestOrHeadlessModeCheck(aClass)) {
          val fixes = if (extensionsCandidates.size == 1) {
            arrayOf<LocalQuickFix>(ConvertToLightServiceFix(psiClass, extension.xmlTag, level))
          }
          else {
            LocalQuickFix.EMPTY_ARRAY
          }
          return registerProblem(aClass, manager, isOnTheFly, fixes)
        }
      }
    }
    return ProblemDescriptor.EMPTY_ARRAY
  }

  private fun registerProblem(aClass: UClass,
                              manager: InspectionManager,
                              isOnTheFly: Boolean,
                              fixes: Array<LocalQuickFix>): Array<ProblemDescriptor> {
    val message = DevKitBundle.message("inspection.light.service.migration.message")
    val holder = createProblemsHolder(aClass, manager, isOnTheFly)
    holder.registerUProblem(aClass, message, *fixes)
    return holder.resultsArray
  }
}