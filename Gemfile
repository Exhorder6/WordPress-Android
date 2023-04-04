# frozen_string_literal: true

source "https://rubygems.org"

gem 'fastlane', '~> 2', '>= 2.197.0'
gem 'nokogiri', '>= 1.13.9'

plugins_path = File.join(File.dirname(__FILE__), 'fastlane', 'Pluginfile')
eval_gemfile(plugins_path) if File.exist?(plugins_path)
